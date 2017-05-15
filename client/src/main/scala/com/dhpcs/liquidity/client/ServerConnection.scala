package com.dhpcs.liquidity.client

import java.io.{File, IOException, InputStream}
import java.util.concurrent.TimeUnit
import javax.net.ssl._

import com.dhpcs.liquidity.client.ServerConnection._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.serialization.ProtoConverter
import com.dhpcs.liquidity.ws.protocol._
import okhttp3.ws.{WebSocket, WebSocketCall, WebSocketListener}
import okhttp3.{OkHttpClient, RequestBody, ResponseBody}
import okio.Buffer

object ServerConnection {

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

    def post(body: => Unit): Unit =
      post(new Runnable {
        override def run(): Unit = body
      })

    def post(runnable: Runnable): Unit
    def quit(): Unit

  }

  sealed abstract class ConnectionState
  case object UNAVAILABLE     extends ConnectionState
  case object GENERAL_FAILURE extends ConnectionState
  case object TLS_ERROR       extends ConnectionState
  case object AVAILABLE       extends ConnectionState
  case object CONNECTING      extends ConnectionState
  case object ONLINE          extends ConnectionState
  case object DISCONNECTING   extends ConnectionState

  trait ConnectionStateListener {
    def onConnectionStateChanged(connectionState: ConnectionState): Unit
  }

  trait NotificationReceiptListener {
    def onZoneNotificationReceived(notification: ZoneNotification): Unit
  }

  class ConnectionRequestToken

  object ResponseCallback {
    def apply(onError: => Unit): ResponseCallback = new ResponseCallback {
      override def onErrorResponse(error: ErrorResponse): Unit = onError
    }
  }

  trait ResponseCallback {

    def onErrorResponse(error: ErrorResponse): Unit
    def onSuccessResponse(success: SuccessResponse): Unit = ()

  }

  private sealed abstract class State
  private sealed abstract class IdleState     extends State
  private case object UnavailableIdleState    extends IdleState
  private case object GeneralFailureIdleState extends IdleState
  private case object TlsErrorIdleState       extends IdleState
  private case object AvailableIdleState      extends IdleState
  private final case class ActiveState(handlerWrapper: HandlerWrapper) extends State {
    var subState: SubState = _
  }
  private sealed abstract class SubState
  private final case class ConnectingSubState(webSocketCall: WebSocketCall) extends SubState
  private sealed abstract class ConnectedSubState extends SubState {
    val webSocket: WebSocket
  }
  private final case class OnlineSubState(webSocket: WebSocket) extends ConnectedSubState
  private case object DisconnectingSubState                     extends SubState

  private sealed abstract class CloseCause
  private case object GeneralFailure   extends CloseCause
  private case object TlsError         extends CloseCause
  private case object ServerDisconnect extends CloseCause
  private case object ClientDisconnect extends CloseCause

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

class ServerConnection(filesDir: File,
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
      .hostnameVerifier(new HostnameVerifier {
        override def verify(s: String, sslSession: SSLSession): Boolean = true
      })
      .readTimeout(0, TimeUnit.SECONDS)
      .writeTimeout(0, TimeUnit.SECONDS)
      .build()
    (clientKeyStore, okHttpClient)
  }

  private[this] val connectivityStatePublisher = connectivityStatePublisherBuilder.build(this)
  private[this] val mainHandlerWrapper         = handlerWrapperFactory.main()

  private[this] var pendingRequests    = Map.empty[Long, ResponseCallback]
  private[this] var nextCorrelationId  = 0L
  private[this] var state: State       = UnavailableIdleState
  private[this] var hasFailed: Boolean = _

  private[this] var _connectionState: ConnectionState = UNAVAILABLE
  private[this] var connectionStateListeners          = Set.empty[ConnectionStateListener]
  private[this] var connectRequestTokens              = Set.empty[ConnectionRequestToken]

  private[this] var notificationReceiptListeners = Set.empty[NotificationReceiptListener]

  handleConnectivityStateChange()

  def clientKey: PublicKey = clientKeyStore.publicKey

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
    if ((_connectionState == ServerConnection.AVAILABLE
        || _connectionState == ServerConnection.GENERAL_FAILURE
        || _connectionState == ServerConnection.TLS_ERROR)
        && (!hasFailed || retry))
      connect()
  }

  def sendCommand(command: Command, responseCallback: ResponseCallback): Unit = state match {
    case _: IdleState =>
      sys.error("Not connected")
    case activeState: ActiveState =>
      activeState.handlerWrapper.post(activeState.subState match {
        case _: ConnectingSubState | DisconnectingSubState =>
          sys.error(s"Not connected")
        case onlineSubState: OnlineSubState =>
          val correlationId = nextCorrelationId
          nextCorrelationId = nextCorrelationId + 1
          try {
            onlineSubState.webSocket.sendMessage(
              RequestBody.create(
                WebSocket.BINARY,
                proto.ws.protocol
                  .Command(
                    correlationId,
                    // TODO: DRY
                    command match {
                      case zoneCommand: ZoneCommand =>
                        proto.ws.protocol.Command.Command.ZoneCommand(
                          proto.ws.protocol.ZoneCommand(
                            ProtoConverter[ZoneCommand, proto.ws.protocol.ZoneCommand.ZoneCommand]
                              .asProto(zoneCommand))
                        )
                    }
                  )
                  .toByteArray
              )
            )
            pendingRequests = pendingRequests + (correlationId -> responseCallback)
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
        if (_connectionState == ServerConnection.CONNECTING
            || _connectionState == ServerConnection.ONLINE)
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
        case AvailableIdleState | GeneralFailureIdleState | TlsErrorIdleState =>
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

  override def onClose(code: Int, reason: String): Unit =
    mainHandlerWrapper.post(state match {
      case _: IdleState =>
        sys.error("Already disconnected")
      case activeState: ActiveState =>
        activeState.handlerWrapper.post(activeState.subState match {
          case _: ConnectingSubState =>
            sys.error("Not connected or disconnecting")
          case _: OnlineSubState =>
            doClose(activeState.handlerWrapper, ServerDisconnect)
          case DisconnectingSubState =>
            doClose(activeState.handlerWrapper, ClientDisconnect)
        })
    })

  override def onFailure(e: IOException, response: okhttp3.Response): Unit = {
    mainHandlerWrapper.post(state match {
      case _: IdleState =>
        sys.error("Already disconnected")
      case activeState: ActiveState =>
        activeState.handlerWrapper.post(activeState.subState match {
          case DisconnectingSubState =>
            doClose(activeState.handlerWrapper, ClientDisconnect)
          case _ =>
            if (response == null)
              e match {
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
  }

  override def onMessage(message: ResponseBody): Unit = {
    val responseWithCorrelationIdOrNotification = message.contentType match {
      case WebSocket.BINARY => proto.ws.protocol.ResponseOrNotification.parseFrom(message.bytes)
      case WebSocket.TEXT   => sys.error("Received text frame")
    }
    mainHandlerWrapper.post(state match {
      case _: IdleState =>
        sys.error("Not connected")
      case activeState: ActiveState =>
        // TODO: DRY
        responseWithCorrelationIdOrNotification.responseOrNotification match {
          case proto.ws.protocol.ResponseOrNotification.ResponseOrNotification.Empty =>
            sys.error("Empty")
          case proto.ws.protocol.ResponseOrNotification.ResponseOrNotification.Response(response) =>
            activeState.handlerWrapper.post(activeState.subState match {
              case _: ConnectingSubState =>
                sys.error("Not connected")
              case _: OnlineSubState =>
                activeState.handlerWrapper.post(pendingRequests.get(response.correlationId) match {
                  case None =>
                    sys.error(s"No pending request exists with correlationId=${response.correlationId}")
                  case Some(responseCallback) =>
                    pendingRequests = pendingRequests - response.correlationId
                    response.response match {
                      case proto.ws.protocol.ResponseOrNotification.Response.Response.Empty =>
                        sys.error("Empty")
                      case proto.ws.protocol.ResponseOrNotification.Response.Response
                            .ZoneResponse(protoZoneResponse) =>
                        val zoneResponse =
                          ProtoConverter[ZoneResponse, proto.ws.protocol.ZoneResponse.ZoneResponse]
                            .asScala(protoZoneResponse.zoneResponse)
                        zoneResponse match {
                          case EmptyZoneResponse =>
                            sys.error("EmptyZoneResponse")
                          case errorResponse: ErrorResponse =>
                            mainHandlerWrapper.post(responseCallback.onErrorResponse(errorResponse))
                          case successResponse: SuccessResponse =>
                            mainHandlerWrapper.post(responseCallback.onSuccessResponse(successResponse))
                        }
                    }
                })
              case DisconnectingSubState =>
            })
          case proto.ws.protocol.ResponseOrNotification.ResponseOrNotification.Notification(notification) =>
            activeState.handlerWrapper.post(notification.notification match {
              case proto.ws.protocol.ResponseOrNotification.Notification.Notification.Empty =>
                sys.error("Empty")
              case proto.ws.protocol.ResponseOrNotification.Notification.Notification.KeepAliveNotification(_) =>
                activeState.subState match {
                  case _: ConnectingSubState =>
                    sys.error("Not connected")
                  case _: OnlineSubState     =>
                  case DisconnectingSubState =>
                }
              case proto.ws.protocol.ResponseOrNotification.Notification.Notification
                    .ZoneNotification(protoZoneNotification) =>
                val zoneNotification =
                  ProtoConverter[ZoneNotification, proto.ws.protocol.ZoneNotification.ZoneNotification]
                    .asScala(protoZoneNotification.zoneNotification)
                activeState.subState match {
                  case _: ConnectingSubState =>
                    sys.error("Not connected")
                  case _: OnlineSubState =>
                    activeState.handlerWrapper.post(
                      mainHandlerWrapper.post(
                        notificationReceiptListeners.foreach(
                          _.onZoneNotificationReceived(zoneNotification)
                        )))
                  case DisconnectingSubState =>
                }
            })
        }
    })
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
            activeState.subState = OnlineSubState(webSocket)
            mainHandlerWrapper.post {
              _connectionState = ONLINE
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
    case AvailableIdleState | GeneralFailureIdleState | TlsErrorIdleState =>
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

  private[this] def doClose(handlerWrapper: HandlerWrapper, closeCause: CloseCause): Unit = {
    handlerWrapper.quit()
    nextCorrelationId = 0
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
    activeState.handlerWrapper.post {
      val webSocketCall = WebSocketCall.create(
        okHttpClient,
        new okhttp3.Request.Builder().url(s"https://$hostname:$port/bws").build
      )
      webSocketCall.enqueue(this)
      activeState.subState = ConnectingSubState(webSocketCall)
    }
    _connectionState = CONNECTING
    connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
  }
}
