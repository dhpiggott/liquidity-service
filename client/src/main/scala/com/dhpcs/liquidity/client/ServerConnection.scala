package com.dhpcs.liquidity.client

import java.io.{File, IOException}
import java.util.UUID
import javax.net.ssl._

import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.client.ServerConnection._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.ws.protocol._
import okhttp3.{OkHttpClient, WebSocket, WebSocketListener}
import okio.ByteString

object ServerConnection {

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
  case object AUTHENTICATING  extends ConnectionState
  case object ONLINE          extends ConnectionState
  case object DISCONNECTING   extends ConnectionState

  trait ConnectionStateListener {
    def onConnectionStateChanged(connectionState: ConnectionState): Unit
  }

  trait NotificationReceiptListener {
    def onZoneNotificationReceived(zoneId: ZoneId, notification: ZoneNotification): Unit
  }

  class ConnectionRequestToken

  // TODO: Swap for returning Future
  trait ResponseCallback {
    def onZoneResponse(zoneResponse: ZoneResponse): Unit
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
  private final case class ConnectingSubState(webSocket: WebSocket) extends SubState
  private sealed abstract class ConnectedSubState extends SubState {
    val webSocket: WebSocket
  }
  private final case class AuthenticatingSubState(webSocket: WebSocket) extends ConnectedSubState
  private final case class OnlineSubState(webSocket: WebSocket)         extends ConnectedSubState
  private case object DisconnectingSubState                             extends SubState

  private sealed abstract class CloseCause
  private case object GeneralFailure   extends CloseCause
  private case object TlsError         extends CloseCause
  private case object ServerDisconnect extends CloseCause
  private case object ClientDisconnect extends CloseCause

}

class ServerConnection(filesDir: File,
                       connectivityStatePublisherBuilder: ConnectivityStatePublisherBuilder,
                       handlerWrapperFactory: HandlerWrapperFactory,
                       scheme: String,
                       hostname: String,
                       port: Int)
    extends WebSocketListener {

  def this(filesDir: File,
           connectivityStatePublisherBuilder: ConnectivityStatePublisherBuilder,
           handlerWrapperFactory: HandlerWrapperFactory,
           scheme: Option[String],
           hostname: Option[String],
           port: Option[Int]) =
    this(
      filesDir,
      connectivityStatePublisherBuilder,
      handlerWrapperFactory,
      scheme.getOrElse("https"),
      hostname.getOrElse("api.liquidityapp.com"),
      port.getOrElse(443)
    )

  def this(filesDir: File,
           connectivityStatePublisherBuilder: ConnectivityStatePublisherBuilder,
           handlerWrapperFactory: HandlerWrapperFactory) =
    this(
      filesDir,
      connectivityStatePublisherBuilder,
      handlerWrapperFactory,
      scheme = None,
      hostname = None,
      port = None
    )

  private[this] lazy val clientKeyStore = ClientKeyStore(filesDir)

  private[this] val okHttpClient = new OkHttpClient()

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
    if ((_connectionState == ServerConnection.AVAILABLE
        || _connectionState == ServerConnection.GENERAL_FAILURE
        || _connectionState == ServerConnection.TLS_ERROR)
        && (!hasFailed || retry))
      connect()
  }

  def sendCreateZoneCommand(createZoneCommand: CreateZoneCommand, responseCallback: ResponseCallback): Unit =
    sendCommand(
      correlationId =>
        proto.ws.protocol.ServerMessage.Command(
          correlationId,
          proto.ws.protocol.ServerMessage.Command.Command.CreateZoneCommand(
            ProtoBinding[CreateZoneCommand, proto.actor.protocol.ZoneCommand.CreateZoneCommand]
              .asProto(createZoneCommand)
          )
      ),
      responseCallback
    )

  def sendZoneCommand(zoneId: ZoneId, zoneCommand: ZoneCommand, responseCallback: ResponseCallback): Unit =
    sendCommand(
      correlationId =>
        proto.ws.protocol.ServerMessage.Command(
          correlationId,
          proto.ws.protocol.ServerMessage.Command.Command.ZoneCommandEnvelope(
            proto.ws.protocol.ServerMessage.Command.ZoneCommandEnvelope(
              zoneId.id.toString,
              Some(ProtoBinding[ZoneCommand, proto.actor.protocol.ZoneCommand]
                .asProto(zoneCommand))
            ))
      ),
      responseCallback
    )

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

  override def onClosed(webSocket: WebSocket, code: Int, reason: String): Unit =
    mainHandlerWrapper.post(state match {
      case _: IdleState =>
        throw new IllegalStateException("Already disconnected")
      case activeState: ActiveState =>
        activeState.handlerWrapper.post(activeState.subState match {
          case _: ConnectingSubState =>
            throw new IllegalStateException("Not connected or disconnecting")
          case _: AuthenticatingSubState | _: OnlineSubState =>
            doClose(activeState.handlerWrapper, ServerDisconnect)
          case DisconnectingSubState =>
            doClose(activeState.handlerWrapper, ClientDisconnect)
        })
    })

  override def onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response): Unit =
    mainHandlerWrapper.post(state match {
      case _: IdleState =>
        throw new IllegalStateException("Already disconnected")
      case activeState: ActiveState =>
        activeState.handlerWrapper.post(activeState.subState match {
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
              } else
              doClose(activeState.handlerWrapper, GeneralFailure)
        })
    })

  override def onMessage(webSocket: WebSocket, bytes: ByteString): Unit = {
    val clientMessage = proto.ws.protocol.ClientMessage.parseFrom(bytes.toByteArray)
    mainHandlerWrapper.post(state match {
      case _: IdleState =>
        throw new IllegalStateException("Not connected")
      case activeState: ActiveState =>
        clientMessage.message match {
          case proto.ws.protocol.ClientMessage.Message.Empty =>
          case proto.ws.protocol.ClientMessage.Message.KeyOwnershipChallenge(keyOwnershipChallenge) =>
            sendServerMessage(
              webSocket,
              proto.ws.protocol.ServerMessage.Message.KeyOwnershipProof(
                createKeyOwnershipProof(clientKeyStore.rsaPublicKey,
                                        clientKeyStore.rsaPrivateKey,
                                        keyOwnershipChallenge)
              )
            )
            activeState.handlerWrapper.post {
              activeState.subState = OnlineSubState(webSocket)
              mainHandlerWrapper.post {
                _connectionState = ONLINE
                connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
              }
            }
          case proto.ws.protocol.ClientMessage.Message.Command(protoCommand) =>
            activeState.handlerWrapper.post(activeState.subState match {
              case _: ConnectingSubState =>
                throw new IllegalStateException("Not connected")
              case _: AuthenticatingSubState | _: OnlineSubState =>
                activeState.handlerWrapper.post(protoCommand.command match {
                  case proto.ws.protocol.ClientMessage.Command.Command.Empty =>
                  case proto.ws.protocol.ClientMessage.Command.Command.PingCommand(_) =>
                    sendServerMessage(
                      webSocket,
                      proto.ws.protocol.ServerMessage.Message.Response(
                        proto.ws.protocol.ServerMessage.Response(
                          protoCommand.correlationId,
                          proto.ws.protocol.ServerMessage.Response.Response.PingResponse(
                            com.google.protobuf.ByteString.EMPTY
                          )
                        ))
                    )
                })
              case DisconnectingSubState =>
            })
          case proto.ws.protocol.ClientMessage.Message.Response(protoResponse) =>
            activeState.handlerWrapper.post(activeState.subState match {
              case _: ConnectingSubState =>
                throw new IllegalStateException("Not connected")
              case _: AuthenticatingSubState =>
                throw new IllegalStateException("Authenticating")
              case _: OnlineSubState =>
                activeState.handlerWrapper.post(pendingRequests.get(protoResponse.correlationId) match {
                  case None =>
                    throw new IllegalStateException(
                      s"No pending request exists with correlationId=${protoResponse.correlationId}")
                  case Some(responseCallback) =>
                    pendingRequests = pendingRequests - protoResponse.correlationId
                    protoResponse.response match {
                      case proto.ws.protocol.ClientMessage.Response.Response.Empty =>
                        throw new IllegalStateException("Empty or unsupported response")
                      case proto.ws.protocol.ClientMessage.Response.Response.ZoneResponse(protoZoneResponse) =>
                        val zoneResponse = ProtoBinding[ZoneResponse, proto.actor.protocol.ZoneResponse.ZoneResponse]
                          .asScala(protoZoneResponse.zoneResponse)
                        mainHandlerWrapper.post(responseCallback.onZoneResponse(zoneResponse))
                    }
                })
              case DisconnectingSubState =>
            })
          case proto.ws.protocol.ClientMessage.Message.Notification(protoNotification) =>
            activeState.handlerWrapper.post(protoNotification.notification match {
              case proto.ws.protocol.ClientMessage.Notification.Notification.Empty =>
              case proto.ws.protocol.ClientMessage.Notification.Notification.EnvelopedZoneNotification(
                  proto.ws.protocol.ClientMessage.Notification.EnvelopedZoneNotification(
                    zoneId,
                    protoZoneNotification
                  )) =>
                val zoneNotification =
                  ProtoBinding[ZoneNotification, Option[proto.actor.protocol.ZoneNotification]]
                    .asScala(protoZoneNotification)
                activeState.subState match {
                  case _: ConnectingSubState =>
                    throw new IllegalStateException("Not connected")
                  case _: AuthenticatingSubState =>
                    throw new IllegalStateException("Authenticating")
                  case _: OnlineSubState =>
                    activeState.handlerWrapper.post(
                      mainHandlerWrapper.post(
                        notificationReceiptListeners.foreach(
                          _.onZoneNotificationReceived(ZoneId(UUID.fromString(zoneId)), zoneNotification)
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
        throw new IllegalStateException("Not connecting")
      case activeState: ActiveState =>
        activeState.handlerWrapper.post(activeState.subState match {
          case _: ConnectedSubState | DisconnectingSubState =>
            throw new IllegalStateException("Not connecting")
          case _: ConnectingSubState =>
            activeState.subState = AuthenticatingSubState(webSocket)
            mainHandlerWrapper.post {
              _connectionState = AUTHENTICATING
              connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
            }
        })
    })

  private[this] def connect(): Unit = state match {
    case _: ActiveState =>
      throw new IllegalStateException("Already connecting/connected/disconnecting")
    case UnavailableIdleState =>
      throw new IllegalStateException("Connection unavailable")
    case AvailableIdleState | GeneralFailureIdleState | TlsErrorIdleState =>
      doOpen()
  }

  private[this] def disconnect(code: Int): Unit = state match {
    case _: IdleState =>
      throw new IllegalStateException("Already disconnected")
    case activeState: ActiveState =>
      activeState.handlerWrapper.post(activeState.subState match {
        case DisconnectingSubState =>
          throw new IllegalStateException("Already disconnecting")
        case ConnectingSubState(webSocket) =>
          activeState.subState = DisconnectingSubState
          mainHandlerWrapper.post {
            _connectionState = DISCONNECTING
            connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
          }
          webSocket.cancel()
        case AuthenticatingSubState(webSocket) =>
          try {
            webSocket.close(code, null); ()
          } catch {
            case _: IOException =>
          }
        case OnlineSubState(webSocket) =>
          activeState.subState = DisconnectingSubState
          mainHandlerWrapper.post {
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

  private[this] def doOpen(): Unit = {
    val activeState = ActiveState(handlerWrapperFactory.create("ServerConnection"))
    state = activeState
    activeState.handlerWrapper.post {
      val webSocket = okHttpClient.newWebSocket(
        new okhttp3.Request.Builder().url(s"$scheme://$hostname:$port/ws").build,
        this
      )
      activeState.subState = ConnectingSubState(webSocket)
    }
    _connectionState = CONNECTING
    connectionStateListeners.foreach(_.onConnectionStateChanged(_connectionState))
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

  private[this] def sendCommand(command: Long => proto.ws.protocol.ServerMessage.Command,
                                responseCallback: ResponseCallback): Unit = state match {
    case _: IdleState =>
      throw new IllegalStateException("Not connected")
    case activeState: ActiveState =>
      activeState.handlerWrapper.post(activeState.subState match {
        case _: ConnectingSubState | DisconnectingSubState =>
          throw new IllegalStateException(s"Not connected")
        case _: AuthenticatingSubState =>
          throw new IllegalStateException("Authenticating")
        case OnlineSubState(webSocket) =>
          val correlationId = nextCorrelationId
          nextCorrelationId = nextCorrelationId + 1
          sendServerMessage(
            webSocket,
            proto.ws.protocol.ServerMessage.Message.Command(command(correlationId)),
            onSuccess = () => pendingRequests = pendingRequests + (correlationId -> responseCallback)
          )
      })
  }

  private[this] def sendServerMessage(webSocket: WebSocket,
                                      message: proto.ws.protocol.ServerMessage.Message,
                                      onSuccess: () => Unit = () => ()): Unit =
    try {
      webSocket.send(ByteString.of(proto.ws.protocol.ServerMessage(message).toByteArray: _*))
      onSuccess()
    } catch {
      // We do nothing here because we count on receiving a call to onFailure due to a matching read error.
      case _: IOException =>
    }

}
