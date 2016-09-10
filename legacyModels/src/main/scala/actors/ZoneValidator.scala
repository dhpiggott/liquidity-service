package actors

import akka.actor.ActorRef
import com.dhpcs.liquidity.models._

object ZoneValidator {

  sealed trait Event {
    def timestamp: Long
  }

  @SerialVersionUID(7985567655962831754L)
  case class ZoneCreatedEvent(timestamp: Long, zone: Zone) extends Event

  @SerialVersionUID(7128730246467326406L)
  case class ZoneJoinedEvent(timestamp: Long, clientConnection: ActorRef, publicKey: PublicKey) extends Event

  @SerialVersionUID(5863809604817765473L)
  case class ZoneQuitEvent(timestamp: Long, clientConnection: ActorRef) extends Event

  @SerialVersionUID(-6517341956799383710L)
  case class ZoneNameChangedEvent(timestamp: Long, name: Option[String]) extends Event

  @SerialVersionUID(1602365713663612201L)
  case class MemberCreatedEvent(timestamp: Long, member: Member) extends Event

  @SerialVersionUID(5262508243607633653L)
  case class MemberUpdatedEvent(timestamp: Long, member: Member) extends Event

  @SerialVersionUID(8097107087241644969L)
  case class AccountCreatedEvent(timestamp: Long, account: Account) extends Event

  @SerialVersionUID(3775208409315288638L)
  case class AccountUpdatedEvent(timestamp: Long, account: Account) extends Event

  @SerialVersionUID(2692499277841584568L)
  case class TransactionAddedEvent(timestamp: Long, transaction: Transaction) extends Event

}
