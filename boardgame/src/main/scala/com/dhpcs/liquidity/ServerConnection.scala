package com.dhpcs.liquidity

import java.io.{File, IOException, InputStream}
import java.util.concurrent.TimeUnit
import javax.net.ssl._

import com.dhpcs.jsonrpc.ResponseCompanion.ErrorResponse
import com.dhpcs.jsonrpc.{JsonRpcMessage, JsonRpcNotificationMessage, JsonRpcRequestMessage, JsonRpcResponseMessage}
import com.dhpcs.liquidity.ServerConnection._
import com.dhpcs.liquidity.model.PublicKey
import com.dhpcs.liquidity.protocol._
import okhttp3.ws.{WebSocket, WebSocketCall, WebSocketListener}
import okhttp3.{OkHttpClient, RequestBody, ResponseBody}
import okio.Buffer
import play.api.libs.json.Json

import scala.util.{Failure, Right, Success, Try}

object ServerConnection {

  trait PRNGFixesApplicator {
    def apply(): Unit
  }

  trait KeyStoreInputStreamProvider {
    def get(): InputStream
  }

  trait ConnectivityStatePublisherBuilder {
    def build(serverConnection: ServerConnection): ConnectivityStatePublisher
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

    def post(body: => Unit): Unit = post(new Runnable {
      override def run(): Unit = body
    })

    def quit(): Unit
  }

  sealed trait ConnectionState

  case object UNAVAILABLE extends ConnectionState

  case object GENERAL_FAILURE extends ConnectionState

  case object TLS_ERROR extends ConnectionState

  case object UNSUPPORTED_VERSION extends ConnectionState

  case object AVAILABLE extends ConnectionState

  case object CONNECTING extends ConnectionState

  case object WAITING_FOR_VERSION_CHECK extends ConnectionState

  case object ONLINE extends ConnectionState

  case object DISCONNECTING extends ConnectionState

  trait ConnectionStateListener {
    def onConnectionStateChanged(connectionState: ConnectionState)
  }

  trait NotificationReceiptListener {
    def onZoneNotificationReceived(zoneNotification: ZoneNotification)
  }

  class ConnectionRequestToken

  trait ResponseCallback {
    def onErrorReceived(errorResponse: ErrorResponse)

    def onResultReceived(resultResponse: ResultResponse) = ()
  }

  private sealed trait State

  private sealed trait IdleState extends State

  private case object UnavailableIdleState extends IdleState

  private case object GeneralFailureIdleState extends IdleState

  private case object TlsErrorIdleState extends IdleState

  private case object UnsupportedVersionIdleState extends IdleState

  private case object AvailableIdleState extends IdleState

  private case class ActiveState(handlerWrapper: HandlerWrapper) extends State {
    var subState: SubState = _
  }

  private sealed trait SubState

  private case class ConnectingSubState(webSocketCall: WebSocketCall) extends SubState

  private sealed trait ConnectedSubState extends SubState {
    val webSocket: WebSocket
  }

  private case class WaitingForVersionCheckSubState(webSocket: WebSocket) extends ConnectedSubState

  private case class OnlineSubState(webSocket: WebSocket) extends ConnectedSubState

  private case object DisconnectingSubState extends SubState

  private case class PendingRequest(requestMessage: JsonRpcRequestMessage,
                                    callback: ResponseCallback)

  private sealed trait CloseCause

  private case object GeneralFailure extends CloseCause

  private case object TlsError extends CloseCause

  private case object UnsupportedVersion extends CloseCause

  private case object ServerDisconnect extends CloseCause

  private case object ClientDisconnect extends CloseCause

  private final val ServerEndpoint = "https://liquidity.dhpcs.com/ws"

  private var instance: ServerConnection = _

  def getInstance(prngFixesApplicator: PRNGFixesApplicator,
                  filesDir: File,
                  keyStoreInputStreamProvider: KeyStoreInputStreamProvider,
                  connectivityStatePublisherBuilder: ConnectivityStatePublisherBuilder,
                  handlerWrapperFactory: HandlerWrapperFactory): ServerConnection = {
    if (instance == null) {
      prngFixesApplicator.apply()
      instance = new ServerConnection(
        filesDir,
        keyStoreInputStreamProvider,
        connectivityStatePublisherBuilder,
        handlerWrapperFactory
      )
    }
    instance
  }

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

  private def readJsonRpcMessage(jsonString: String): Either[String, JsonRpcMessage] =
    Try(Json.parse(jsonString)) match {
      case Failure(exception) =>
        Left(s"Invalid JSON: $exception")
      case Success(json) =>
        Json.fromJson[JsonRpcMessage](json).fold(
          errors => Left(s"Invalid JSON-RPC message: $errors"),
          Right(_)
        )
    }
}

class ServerConnection private(filesDir: File,
                               keyStoreInputStreamProvider: KeyStoreInputStreamProvider,
                               connectivityStatePublisherBuilder: ConnectivityStatePublisherBuilder,
                               handlerWrapperFactory: HandlerWrapperFactory)
  extends WebSocketListener {
  private[this] lazy val client = {
    val trustManager = ServerTrust.getTrustManager(keyStoreInputStreamProvider.get())
    new OkHttpClient.Builder()
      .sslSocketFactory(createSslSocketFactory(
        ClientKey.getKeyManagers(filesDir),
        Array(trustManager)
      ), trustManager)
      .readTimeout(0, TimeUnit.SECONDS)
      .writeTimeout(0, TimeUnit.SECONDS)
      .build()
  }

  private[this] val connectivityStatePublisher = connectivityStatePublisherBuilder.build(this)
  private[this] val mainHandlerWrapper = handlerWrapperFactory.main()

  private[this] var pendingRequests = Map.empty[BigDecimal, PendingRequest]
  private[this] var commandIdentifier = BigDecimal(0)
  private[this] var state: State = UnavailableIdleState
  private[this] var hasFailed: Boolean = _

  private[this] var _connectionState: ConnectionState = UNAVAILABLE
  private[this] var connectionStateListeners = Set.empty[ConnectionStateListener]
  private[this] var connectRequestTokens = Set.empty[ConnectionRequestToken]

  private[this] var notificationReceiptListeners = Set.empty[NotificationReceiptListener]

  handleConnectivityStateChange()

  def clientKey: PublicKey = ClientKey.getPublicKey(filesDir)

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
    if (!notificationReceiptListeners.contains(listener)) {
      notificationReceiptListeners = notificationReceiptListeners + listener
    }

  def requestConnection(token: ConnectionRequestToken, retry: Boolean): Unit = {
    if (!connectRequestTokens.contains(token)) {
      connectRequestTokens = connectRequestTokens + token
    }
    if ((_connectionState == ServerConnection.AVAILABLE
      || _connectionState == ServerConnection.GENERAL_FAILURE
      || _connectionState == ServerConnection.TLS_ERROR
      || _connectionState == ServerConnection.UNSUPPORTED_VERSION)
      && (!hasFailed || retry)) {
      connect()
    }
  }

  def sendCommand(command: Command, responseCallback: ResponseCallback): Unit = state match {
    case _: IdleState =>
      sys.error("Not connected")
    case activeState: ActiveState =>
      activeState.handlerWrapper.post(activeState.subState match {
        case _: ConnectingSubState | DisconnectingSubState =>
          sys.error(s"Not connected")
        case _: WaitingForVersionCheckSubState =>
          sys.error("Waiting for version check")
        case onlineSubState: OnlineSubState =>
          val jsonRpcRequestMessage = Command.write(command, Some(Right(commandIdentifier)))
          commandIdentifier = commandIdentifier + 1
          try {
            onlineSubState.webSocket.sendMessage(
              RequestBody.create(
                WebSocket.TEXT,
                Json.stringify(
                  Json.toJson(jsonRpcRequestMessage)
                )
              )
            )
            pendingRequests = pendingRequests +
              (jsonRpcRequestMessage.id.get.right.get ->
                PendingRequest(jsonRpcRequestMessage, responseCallback))
          } catch {
            // We do nothing here because we count on receiving a call to onFailure due to a matching read error.
            case _: IOException =>
          }
      })
  }

  def unrequestConnection(token: ConnectionRequestToken): Unit =
    if (connectRequestTokens.contains(token)) {
      connectRequestTokens = connectRequestTokens - token
      if (connectRequestTokens.isEmpty) {
        if (_connectionState == ServerConnection.CONNECTING
          || _connectionState == ServerConnection.WAITING_FOR_VERSION_CHECK
          || _connectionState == ServerConnection.ONLINE) {
          disconnect(1001)
        }
      }
    }

  def unregisterListener(listener: NotificationReceiptListener): Unit =
    if (notificationReceiptListeners.contains(listener)) {
      notificationReceiptListeners = notificationReceiptListeners - listener
    }

  def unregisterListener(listener: ConnectionStateListener): Unit =
    if (connectionStateListeners.contains(listener)) {
      connectionStateListeners = connectionStateListeners - listener
      if (connectionStateListeners.isEmpty) {
        connectivityStatePublisher.unregister()
      }
    }

  def handleConnectivityStateChange(): Unit =
    if (!connectivityStatePublisher.isConnectionAvailable) {
      state match {
        case AvailableIdleState
             | GeneralFailureIdleState
             | TlsErrorIdleState
             | UnsupportedVersionIdleState =>

          state = UnavailableIdleState
          _connectionState = UNAVAILABLE
          connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
        case _ =>
      }
    } else {
      state match {
        case UnavailableIdleState =>
          state = AvailableIdleState
          _connectionState = AVAILABLE
          connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
        case _ =>
      }
    }

  override def onClose(code: Int, reason: String): Unit =
    mainHandlerWrapper.post(state match {
      case _: IdleState =>
        sys.error("Already disconnected")
      case activeState: ActiveState =>
        activeState.handlerWrapper.post(activeState.subState match {
          case _: ConnectingSubState =>
            sys.error("Not connected or disconnecting")
          case _: WaitingForVersionCheckSubState =>
            doClose(
              activeState.handlerWrapper,
              UnsupportedVersion
            )
          case _: OnlineSubState =>
            doClose(
              activeState.handlerWrapper,
              ServerDisconnect
            )
          case DisconnectingSubState =>
            doClose(
              activeState.handlerWrapper,
              ClientDisconnect
            )
        })
    })

  override def onFailure(e: IOException, response: okhttp3.Response): Unit =
    mainHandlerWrapper.post(state match {
      case _: IdleState =>
        sys.error("Already disconnected")
      case activeState: ActiveState =>
        activeState.handlerWrapper.post(activeState.subState match {
          case DisconnectingSubState =>
            doClose(
              activeState.handlerWrapper,
              ClientDisconnect
            )
          case _ =>
            if (response == null) {
              e match {
                case _: SSLException =>
                  // Client rejected server certificate.
                  doClose(
                    activeState.handlerWrapper,
                    TlsError
                  )
                case _ =>
                  doClose(
                    activeState.handlerWrapper,
                    GeneralFailure
                  )
              }
            } else {
              if (response.code == 400) {
                // Server rejected client certificate.
                doClose(
                  activeState.handlerWrapper,
                  TlsError
                )
              } else {
                doClose(
                  activeState.handlerWrapper,
                  GeneralFailure
                )
              }
            }
        })
    })

  override def onMessage(message: ResponseBody): Unit = message.contentType match {
    case WebSocket.BINARY =>
      sys.error("Received binary frame")
    case WebSocket.TEXT =>
      readJsonRpcMessage(message.string) match {
        case Left(error) =>
          sys.error(error)
        case Right(jsonRpcMessage) =>
          mainHandlerWrapper.post(state match {
            case _: IdleState =>
              sys.error("Not connected")
            case activeState: ActiveState =>
              jsonRpcMessage match {
                case jsonRpcNotificationMessage: JsonRpcNotificationMessage =>
                  activeState.handlerWrapper.post {
                    Notification.read(jsonRpcNotificationMessage).fold(
                      ifEmpty = sys.error(s"No notification type exists with method" +
                        s"=${jsonRpcNotificationMessage.method}")
                    )(_.fold(
                      errors => sys.error(s"Invalid Notification: $errors"), {
                        case SupportedVersionsNotification(compatibleVersionNumbers) =>
                          activeState.subState match {
                            case _: ConnectingSubState =>
                              sys.error("Not connected")
                            case _: OnlineSubState =>
                              sys.error("Already online")
                            case WaitingForVersionCheckSubState(webSocket) =>
                              if (!compatibleVersionNumbers.contains(VersionNumber)) {
                                mainHandlerWrapper.post(
                                  disconnect(1001)
                                )
                              } else {
                                activeState.handlerWrapper.post {
                                  activeState.subState = OnlineSubState(webSocket)
                                  mainHandlerWrapper.post {
                                    _connectionState = ONLINE
                                    connectionStateListeners.foreach(
                                      _.onConnectionStateChanged(_connectionState)
                                    )
                                  }
                                }
                              }
                            case DisconnectingSubState =>
                          }
                        case KeepAliveNotification =>
                          activeState.subState match {
                            case _: ConnectingSubState =>
                              sys.error("Not connected")
                            case _: WaitingForVersionCheckSubState =>
                              sys.error("Waiting for version check")
                            case _: OnlineSubState =>
                            case DisconnectingSubState =>
                          }
                        case zoneNotification: ZoneNotification =>
                          activeState.subState match {
                            case _: ConnectingSubState =>
                              sys.error("Not connected")
                            case _: WaitingForVersionCheckSubState =>
                              sys.error("Waiting for version check")
                            case _: OnlineSubState =>
                              activeState.handlerWrapper.post(
                                mainHandlerWrapper.post(
                                  notificationReceiptListeners.foreach(
                                    _.onZoneNotificationReceived(zoneNotification)
                                  )
                                )
                              )
                            case DisconnectingSubState =>
                          }
                      }))
                  }
                case jsonRpcResponseMessage: JsonRpcResponseMessage =>
                  activeState.handlerWrapper.post(activeState.subState match {
                    case _: ConnectingSubState =>
                      sys.error("Not connected")
                    case _: WaitingForVersionCheckSubState =>
                      sys.error("Waiting for version check")
                    case _: OnlineSubState =>
                      jsonRpcResponseMessage.id.fold(
                        ifEmpty = sys.error(s"JSON-RPC message ID missing, jsonRpcResponseMessage.eitherErrorOrResult" +
                          s"=${jsonRpcResponseMessage.eitherErrorOrResult}")
                      )(id =>
                        id.right.toOption.fold(
                          ifEmpty = sys.error(s"JSON-RPC message ID was not a number, id=$id")
                        )(commandIdentifier =>
                          activeState.handlerWrapper.post(
                            pendingRequests.get(commandIdentifier).fold(
                              ifEmpty = sys.error(s"No pending request exists with commandIdentifier" +
                                s"=$commandIdentifier")
                            ) { pendingRequest =>
                              pendingRequests = pendingRequests - commandIdentifier
                              Response.read(
                                jsonRpcResponseMessage,
                                pendingRequest.requestMessage.method
                              ).fold(
                                errors => sys.error(s"Invalid Response: $errors"), {
                                  case Left(errorResponse) =>
                                    mainHandlerWrapper.post(
                                      pendingRequest.callback.onErrorReceived(errorResponse)
                                    )
                                  case Right(resultResponse) =>
                                    mainHandlerWrapper.post(
                                      pendingRequest.callback.onResultReceived(resultResponse)
                                    )
                                })
                            }
                          )
                        )
                      )
                    case DisconnectingSubState =>
                  })
                case jsonRpc_Message =>
                  activeState.handlerWrapper.post(activeState.subState match {
                    case _: ConnectingSubState =>
                      sys.error("Not connected")
                    case _: WaitingForVersionCheckSubState =>
                      sys.error("Waiting for version check")
                    case _: OnlineSubState =>
                      sys.error(s"Received $jsonRpc_Message")
                    case DisconnectingSubState =>
                  })
              }
          })
      }
  }

  override def onOpen(webSocket: WebSocket, response: okhttp3.Response): Unit =
    mainHandlerWrapper.post(state match {
      case _: IdleState =>
        sys.error("Not connecting")
      case activeState: ActiveState =>
        activeState.handlerWrapper.post(activeState.subState match {
          case _: ConnectedSubState | DisconnectingSubState =>
            sys.error("Not connecting")
          case _: ConnectingSubState =>
            activeState.subState = WaitingForVersionCheckSubState(webSocket)
            mainHandlerWrapper.post {
              _connectionState = WAITING_FOR_VERSION_CHECK
              connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
            }
        })
    })

  override def onPong(payload: Buffer): Unit = ()

  private[this] def connect(): Unit = state match {
    case _: ActiveState =>
      sys.error("Already connecting/connected/disconnecting")
    case UnavailableIdleState =>
      sys.error("Connection unavailable")
    case AvailableIdleState
         | GeneralFailureIdleState
         | TlsErrorIdleState
         | UnsupportedVersionIdleState =>
      doOpen()
  }

  private[this] def disconnect(code: Int): Unit = state match {
    case _: IdleState =>
      sys.error("Already disconnected")
    case activeState: ActiveState =>
      activeState.handlerWrapper.post(activeState.subState match {
        case DisconnectingSubState =>
          sys.error("Already disconnecting")
        case ConnectingSubState(webSocketCall) =>
          activeState.subState = DisconnectingSubState
          mainHandlerWrapper.post {
            _connectionState = DISCONNECTING
            connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
          }
          webSocketCall.cancel()
        case WaitingForVersionCheckSubState(webSocket) =>
          try webSocket.close(code, null)
          catch {
            case _: IOException =>
          }
        case OnlineSubState(webSocket) =>
          activeState.subState = DisconnectingSubState
          mainHandlerWrapper.post {
            _connectionState = DISCONNECTING
            connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
          }
          try webSocket.close(code, null)
          catch {
            case _: IOException =>
          }
      })
  }

  private[this] def doClose(handlerWrapper: HandlerWrapper,
                            closeCause: CloseCause,
                            reconnect: Boolean = false): Unit = {
    handlerWrapper.quit()
    commandIdentifier = 0
    pendingRequests = Map.empty
    mainHandlerWrapper.post {
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
          if (connectRequestTokens.nonEmpty) {
            doOpen()
          } else {
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
    activeState.handlerWrapper.post {
      val webSocketCall = WebSocketCall.create(
        client,
        new okhttp3.Request.Builder().url(ServerEndpoint).build
      )
      webSocketCall.enqueue(this)
      activeState.subState = ConnectingSubState(webSocketCall)
    }
    _connectionState = CONNECTING
    connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
  }
}
