package com.dhpcs.liquidity.client

import java.io.{File, IOException, InputStream}
import java.util.concurrent.TimeUnit
import javax.net.ssl._

import com.dhpcs.jsonrpc.JsonRpcMessage.{NoCorrelationId, NumericCorrelationId, StringCorrelationId}
import com.dhpcs.jsonrpc._
import com.dhpcs.liquidity.client.LegacyServerConnection._
import com.dhpcs.liquidity.model.PublicKey
import com.dhpcs.liquidity.ws.protocol.legacy._
import okhttp3.{OkHttpClient, WebSocket, WebSocketListener}
import play.api.libs.json.{JsError, JsSuccess, Json}

object LegacyServerConnection {

  trait KeyStoreInputStreamProvider {
    def get(): InputStream
  }

  trait ConnectivityStatePublisherBuilder {
    def build(serverConnection: LegacyServerConnection): ConnectivityStatePublisher
  }

  trait ConnectivityStatePublisher {

    def isConnectionAvailable: Boolean
    def register(): Unit
    def unregister(): Unit

  }

  trait HandlerWrapperFactory {

    def create(name: String): HandlerWrapper
    def main(): HandlerWrapper

  }

  abstract class HandlerWrapper {

    def post(runnable: Runnable): Unit
    def quit(): Unit

  }

  sealed abstract class ConnectionState
  case object UNAVAILABLE               extends ConnectionState
  case object GENERAL_FAILURE           extends ConnectionState
  case object TLS_ERROR                 extends ConnectionState
  case object UNSUPPORTED_VERSION       extends ConnectionState
  case object AVAILABLE                 extends ConnectionState
  case object CONNECTING                extends ConnectionState
  case object WAITING_FOR_VERSION_CHECK extends ConnectionState
  case object ONLINE                    extends ConnectionState
  case object DISCONNECTING             extends ConnectionState

  trait ConnectionStateListener {
    def onConnectionStateChanged(connectionState: ConnectionState): Unit
  }

  trait NotificationReceiptListener {
    def onZoneNotificationReceived(notification: LegacyWsProtocol.ZoneNotification): Unit
  }

  class ConnectionRequestToken

  trait ResponseCallback {

    def onErrorResponse(error: LegacyWsProtocol.ErrorResponse): Unit
    def onSuccessResponse(success: LegacyWsProtocol.SuccessResponse): Unit

  }

  private sealed abstract class State
  private sealed abstract class IdleState         extends State
  private case object UnavailableIdleState        extends IdleState
  private case object GeneralFailureIdleState     extends IdleState
  private case object TlsErrorIdleState           extends IdleState
  private case object UnsupportedVersionIdleState extends IdleState
  private case object AvailableIdleState          extends IdleState
  private final case class ActiveState(handlerWrapper: HandlerWrapper) extends State {
    var subState: SubState = _
  }
  private sealed abstract class SubState
  private final case class ConnectingSubState(webSocket: WebSocket) extends SubState
  private sealed abstract class ConnectedSubState extends SubState {
    val webSocket: WebSocket
  }
  private final case class WaitingForVersionCheckSubState(webSocket: WebSocket) extends ConnectedSubState
  private final case class OnlineSubState(webSocket: WebSocket)                 extends ConnectedSubState
  private case object DisconnectingSubState                                     extends SubState

  private final case class PendingRequest(requestMessage: JsonRpcRequestMessage, callback: ResponseCallback)

  private sealed abstract class CloseCause
  private case object GeneralFailure     extends CloseCause
  private case object TlsError           extends CloseCause
  private case object UnsupportedVersion extends CloseCause
  private case object ServerDisconnect   extends CloseCause
  private case object ClientDisconnect   extends CloseCause

  private def createSslSocketFactory(keyManagers: Array[KeyManager],
                                     trustManagers: Array[TrustManager]): SSLSocketFactory = {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(
      keyManagers,
      trustManagers,
      null
    )
    sslContext.getSocketFactory
  }
}

class LegacyServerConnection(filesDir: File,
                             keyStoreInputStreamProvider: KeyStoreInputStreamProvider,
                             connectivityStatePublisherBuilder: ConnectivityStatePublisherBuilder,
                             handlerWrapperFactory: HandlerWrapperFactory,
                             hostname: String,
                             port: Int)
    extends WebSocketListener {

  def this(filesDir: File,
           keyStoreInputStreamProvider: KeyStoreInputStreamProvider,
           connectivityStatePublisherBuilder: ConnectivityStatePublisherBuilder,
           handlerWrapperFactory: HandlerWrapperFactory,
           hostname: Option[String],
           port: Option[Int]) =
    this(
      filesDir,
      keyStoreInputStreamProvider,
      connectivityStatePublisherBuilder,
      handlerWrapperFactory,
      hostname.getOrElse("liquidity.dhpcs.com"),
      port.getOrElse(443)
    )

  def this(filesDir: File,
           keyStoreInputStreamProvider: KeyStoreInputStreamProvider,
           connectivityStatePublisherBuilder: ConnectivityStatePublisherBuilder,
           handlerWrapperFactory: HandlerWrapperFactory) =
    this(
      filesDir,
      keyStoreInputStreamProvider,
      connectivityStatePublisherBuilder,
      handlerWrapperFactory,
      hostname = None,
      port = None
    )

  private[this] lazy val (clientKeyStore, okHttpClient) = {
    val clientKeyStore     = ClientKeyStore(filesDir)
    val serverTrustManager = ServerTrustManager(keyStoreInputStreamProvider.get())
    val okHttpClient = new OkHttpClient.Builder()
      .sslSocketFactory(createSslSocketFactory(
                          clientKeyStore.keyManagers,
                          Array(serverTrustManager)
                        ),
                        serverTrustManager)
      .hostnameVerifier((_: String, _: SSLSession) => true)
      .readTimeout(0, TimeUnit.SECONDS)
      .writeTimeout(0, TimeUnit.SECONDS)
      .build()
    (clientKeyStore, okHttpClient)
  }

  private[this] val connectivityStatePublisher = connectivityStatePublisherBuilder.build(this)
  private[this] val mainHandlerWrapper         = handlerWrapperFactory.main()

  private[this] var pendingRequests    = Map.empty[BigDecimal, PendingRequest]
  private[this] var nextCorrelationId  = 0L
  private[this] var state: State       = UnavailableIdleState
  private[this] var hasFailed: Boolean = _

  private[this] var _connectionState: ConnectionState = UNAVAILABLE
  private[this] var connectionStateListeners          = Set.empty[ConnectionStateListener]
  private[this] var connectRequestTokens              = Set.empty[ConnectionRequestToken]

  private[this] var notificationReceiptListeners = Set.empty[NotificationReceiptListener]

  handleConnectivityStateChange()

  lazy val clientKey: PublicKey = PublicKey(clientKeyStore.rsaPublicKey.getEncoded)

  def connectionState: ConnectionState = _connectionState

  def registerListener(listener: ConnectionStateListener): Unit =
    if (!connectionStateListeners.contains(listener)) {
      if (connectionStateListeners.isEmpty) {
        connectivityStatePublisher.register()
        handleConnectivityStateChange()
      }
      connectionStateListeners = connectionStateListeners + listener
      listener.onConnectionStateChanged(_connectionState)
    }

  def registerListener(listener: NotificationReceiptListener): Unit =
    if (!notificationReceiptListeners.contains(listener))
      notificationReceiptListeners = notificationReceiptListeners + listener

  def requestConnection(token: ConnectionRequestToken, retry: Boolean): Unit = {
    if (!connectRequestTokens.contains(token))
      connectRequestTokens = connectRequestTokens + token
    if ((_connectionState == LegacyServerConnection.AVAILABLE
        || _connectionState == LegacyServerConnection.GENERAL_FAILURE
        || _connectionState == LegacyServerConnection.TLS_ERROR
        || _connectionState == LegacyServerConnection.UNSUPPORTED_VERSION)
        && (!hasFailed || retry))
      connect()
  }

  def sendCommand(command: LegacyWsProtocol.Command, responseCallback: ResponseCallback): Unit = state match {
    case _: IdleState =>
      throw new IllegalStateException("Not connected")
    case activeState: ActiveState =>
      activeState.handlerWrapper.post(() =>
        activeState.subState match {
          case _: ConnectingSubState | DisconnectingSubState =>
            throw new IllegalStateException(s"Not connected")
          case _: WaitingForVersionCheckSubState =>
            throw new IllegalStateException("Waiting for version check")
          case onlineSubState: OnlineSubState =>
            val correlationId = NumericCorrelationId(nextCorrelationId)
            nextCorrelationId = nextCorrelationId + 1
            val jsonRpcRequestMessage = LegacyWsProtocol.Command.write(command, correlationId)
            try {
              onlineSubState.webSocket.send(
                Json.stringify(Json.toJson(jsonRpcRequestMessage))
              )
              pendingRequests = pendingRequests +
                (correlationId.value -> PendingRequest(jsonRpcRequestMessage, responseCallback))
            } catch {
              // We do nothing here because we count on receiving a call to onFailure due to a matching read error.
              case _: IOException =>
            }
      })
  }

  def unrequestConnection(token: ConnectionRequestToken): Unit =
    if (connectRequestTokens.contains(token)) {
      connectRequestTokens = connectRequestTokens - token
      if (connectRequestTokens.isEmpty)
        if (_connectionState == LegacyServerConnection.CONNECTING
            || _connectionState == LegacyServerConnection.WAITING_FOR_VERSION_CHECK
            || _connectionState == LegacyServerConnection.ONLINE)
          disconnect(1001)
    }

  def unregisterListener(listener: NotificationReceiptListener): Unit =
    if (notificationReceiptListeners.contains(listener))
      notificationReceiptListeners = notificationReceiptListeners - listener

  def unregisterListener(listener: ConnectionStateListener): Unit =
    if (connectionStateListeners.contains(listener)) {
      connectionStateListeners = connectionStateListeners - listener
      if (connectionStateListeners.isEmpty)
        connectivityStatePublisher.unregister()
    }

  def handleConnectivityStateChange(): Unit =
    if (!connectivityStatePublisher.isConnectionAvailable)
      state match {
        case AvailableIdleState | GeneralFailureIdleState | TlsErrorIdleState | UnsupportedVersionIdleState =>
          state = UnavailableIdleState
          _connectionState = UNAVAILABLE
          connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
        case _ =>
      } else
      state match {
        case UnavailableIdleState =>
          state = AvailableIdleState
          _connectionState = AVAILABLE
          connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
        case _ =>
      }

  override def onClosed(webSocket: WebSocket, code: Int, reason: String): Unit =
    mainHandlerWrapper.post(() =>
      state match {
        case _: IdleState =>
          throw new IllegalStateException("Already disconnected")
        case activeState: ActiveState =>
          activeState.handlerWrapper.post(() =>
            activeState.subState match {
              case _: ConnectingSubState =>
                throw new IllegalStateException("Not connected or disconnecting")
              case _: WaitingForVersionCheckSubState =>
                doClose(activeState.handlerWrapper, UnsupportedVersion)
              case _: OnlineSubState =>
                doClose(activeState.handlerWrapper, ServerDisconnect)
              case DisconnectingSubState =>
                doClose(activeState.handlerWrapper, ClientDisconnect)
          })
    })

  override def onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response): Unit =
    mainHandlerWrapper.post(() =>
      state match {
        case _: IdleState =>
          throw new IllegalStateException("Already disconnected")
        case activeState: ActiveState =>
          activeState.handlerWrapper.post(() =>
            activeState.subState match {
              case DisconnectingSubState =>
                doClose(activeState.handlerWrapper, ClientDisconnect)
              case _ =>
                if (response == null)
                  t match {
                    case _: SSLException =>
                      // Client rejected server certificate.
                      doClose(activeState.handlerWrapper, TlsError)
                    case _ =>
                      doClose(activeState.handlerWrapper, GeneralFailure)
                  } else if (response.code == 400)
                  // Server rejected client certificate.
                  doClose(activeState.handlerWrapper, TlsError)
                else
                  doClose(activeState.handlerWrapper, GeneralFailure)
          })
    })

  override def onMessage(webSocket: WebSocket, message: String): Unit = {
    val jsonRpcMessage = Json.parse(message).as[JsonRpcMessage]
    mainHandlerWrapper.post(() =>
      state match {
        case _: IdleState =>
          throw new IllegalStateException("Not connected")
        case activeState: ActiveState =>
          jsonRpcMessage match {
            case jsonRpcNotificationMessage: JsonRpcNotificationMessage =>
              activeState.handlerWrapper.post(() =>
                LegacyWsProtocol.Notification.read(jsonRpcNotificationMessage) match {
                  case JsError(errors) =>
                    throw new IllegalStateException(s"Invalid Notification: $errors")
                  case JsSuccess(value, _) =>
                    value match {
                      case LegacyWsProtocol.SupportedVersionsNotification(compatibleVersionNumbers) =>
                        activeState.subState match {
                          case _: ConnectingSubState =>
                            throw new IllegalStateException("Not connected")
                          case _: OnlineSubState =>
                            throw new IllegalStateException("Already online")
                          case _: WaitingForVersionCheckSubState =>
                            if (!compatibleVersionNumbers.contains(VersionNumber))
                              mainHandlerWrapper.post(() => disconnect(1001))
                            else
                              activeState.handlerWrapper.post { () =>
                                activeState.subState = OnlineSubState(webSocket)
                                mainHandlerWrapper.post { () =>
                                  _connectionState = ONLINE
                                  connectionStateListeners.foreach(
                                    _.onConnectionStateChanged(_connectionState)
                                  )
                                }
                              }
                          case DisconnectingSubState =>
                        }
                      case LegacyWsProtocol.KeepAliveNotification =>
                        activeState.subState match {
                          case _: ConnectingSubState =>
                            throw new IllegalStateException("Not connected")
                          case _: WaitingForVersionCheckSubState =>
                            throw new IllegalStateException("Waiting for version check")
                          case _: OnlineSubState     =>
                          case DisconnectingSubState =>
                        }
                      case zoneNotification: LegacyWsProtocol.ZoneNotification =>
                        activeState.subState match {
                          case _: ConnectingSubState =>
                            throw new IllegalStateException("Not connected")
                          case _: WaitingForVersionCheckSubState =>
                            throw new IllegalStateException("Waiting for version check")
                          case _: OnlineSubState =>
                            activeState.handlerWrapper.post(
                              () =>
                                mainHandlerWrapper.post(
                                  () =>
                                    notificationReceiptListeners.foreach(
                                      _.onZoneNotificationReceived(zoneNotification)
                                  )))
                          case DisconnectingSubState =>
                        }
                    }
              })
            case jsonRpcResponseMessage: JsonRpcResponseMessage =>
              activeState.handlerWrapper.post(() =>
                activeState.subState match {
                  case _: ConnectingSubState =>
                    throw new IllegalStateException("Not connected")
                  case _: WaitingForVersionCheckSubState =>
                    throw new IllegalStateException("Waiting for version check")
                  case _: OnlineSubState =>
                    jsonRpcResponseMessage.id match {
                      case NoCorrelationId =>
                        throw new IllegalStateException(
                          s"JSON-RPC message ID missing, jsonRpcResponseMessage=$jsonRpcResponseMessage")
                      case StringCorrelationId(value) =>
                        throw new IllegalStateException(s"JSON-RPC message ID was not a number, id=$value")
                      case NumericCorrelationId(value) =>
                        activeState.handlerWrapper.post(() =>
                          pendingRequests.get(value) match {
                            case None =>
                              throw new IllegalStateException(
                                s"No pending request exists with commandIdentifier=$value")
                            case Some(pendingRequest) =>
                              pendingRequests = pendingRequests - value
                              jsonRpcResponseMessage match {
                                case jsonRpcResponseErrorMessage: JsonRpcResponseErrorMessage =>
                                  mainHandlerWrapper.post(
                                    () =>
                                      pendingRequest.callback.onErrorResponse(
                                        LegacyWsProtocol.ErrorResponse(jsonRpcResponseErrorMessage.message)))
                                case jsonRpcResponseSuccessMessage: JsonRpcResponseSuccessMessage =>
                                  LegacyWsProtocol.SuccessResponse
                                    .read(jsonRpcResponseSuccessMessage, pendingRequest.requestMessage.method) match {
                                    case JsError(errors) =>
                                      throw new IllegalStateException(s"Invalid Response: $errors")
                                    case JsSuccess(response, _) =>
                                      mainHandlerWrapper.post(() => pendingRequest.callback.onSuccessResponse(response))
                                  }
                              }
                        })
                    }
                  case DisconnectingSubState =>
              })
            case _ =>
              activeState.handlerWrapper.post(() =>
                activeState.subState match {
                  case _: ConnectingSubState =>
                    throw new IllegalStateException("Not connected")
                  case _: WaitingForVersionCheckSubState =>
                    throw new IllegalStateException("Waiting for version check")
                  case _: OnlineSubState =>
                    throw new IllegalStateException(s"Received $jsonRpcMessage")
                  case DisconnectingSubState =>
              })
          }
    })
  }

  override def onOpen(webSocket: WebSocket, response: okhttp3.Response): Unit =
    mainHandlerWrapper.post(() =>
      state match {
        case _: IdleState =>
          throw new IllegalStateException("Not connecting")
        case activeState: ActiveState =>
          activeState.handlerWrapper.post(() =>
            activeState.subState match {
              case _: ConnectedSubState | DisconnectingSubState =>
                throw new IllegalStateException("Not connecting")
              case _: ConnectingSubState =>
                activeState.subState = WaitingForVersionCheckSubState(webSocket)
                mainHandlerWrapper.post { () =>
                  _connectionState = WAITING_FOR_VERSION_CHECK
                  connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
                }
          })
    })

  private[this] def connect(): Unit = state match {
    case _: ActiveState =>
      throw new IllegalStateException("Already connecting/connected/disconnecting")
    case UnavailableIdleState =>
      throw new IllegalStateException("Connection unavailable")
    case AvailableIdleState | GeneralFailureIdleState | TlsErrorIdleState | UnsupportedVersionIdleState =>
      doOpen()
  }

  private[this] def disconnect(code: Int): Unit = state match {
    case _: IdleState =>
      throw new IllegalStateException("Already disconnected")
    case activeState: ActiveState =>
      activeState.handlerWrapper.post(() =>
        activeState.subState match {
          case DisconnectingSubState =>
            throw new IllegalStateException("Already disconnecting")
          case ConnectingSubState(webSocket) =>
            activeState.subState = DisconnectingSubState
            mainHandlerWrapper.post { () =>
              _connectionState = DISCONNECTING
              connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
            }
            webSocket.cancel()
          case WaitingForVersionCheckSubState(webSocket) =>
            try {
              webSocket.close(code, null); ()
            } catch {
              case _: IOException =>
            }
          case OnlineSubState(webSocket) =>
            activeState.subState = DisconnectingSubState
            mainHandlerWrapper.post { () =>
              _connectionState = DISCONNECTING
              connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
            }
            try {
              webSocket.close(code, null); ()
            } catch {
              case _: IOException =>
            }
      })
  }

  private[this] def doClose(handlerWrapper: HandlerWrapper, closeCause: CloseCause): Unit = {
    handlerWrapper.quit()
    nextCorrelationId = 0
    pendingRequests = Map.empty
    mainHandlerWrapper.post { () =>
      closeCause match {
        case GeneralFailure =>
          hasFailed = true
          state = GeneralFailureIdleState
          _connectionState = GENERAL_FAILURE
          connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
        case TlsError =>
          hasFailed = true
          state = TlsErrorIdleState
          _connectionState = TLS_ERROR
          connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
        case UnsupportedVersion =>
          hasFailed = true
          state = UnsupportedVersionIdleState
          _connectionState = UNSUPPORTED_VERSION
          connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
        case ServerDisconnect =>
          hasFailed = true
          state = AvailableIdleState
          _connectionState = AVAILABLE
          connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
        case ClientDisconnect =>
          hasFailed = false
          if (connectRequestTokens.nonEmpty)
            doOpen()
          else {
            state = AvailableIdleState
            _connectionState = AVAILABLE
            connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
          }
      }
    }
  }

  private[this] def doOpen(): Unit = {
    val activeState = ActiveState(handlerWrapperFactory.create("ServerConnection"))
    state = activeState
    activeState.handlerWrapper.post { () =>
      val webSocket = okHttpClient.newWebSocket(
        new okhttp3.Request.Builder().url(s"https://$hostname:$port/ws").build,
        this
      )
      activeState.subState = ConnectingSubState(webSocket)
    }
    _connectionState = CONNECTING
    connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
  }
}
