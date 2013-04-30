/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.InetSocketAddress
import java.io.IOException
import java.nio.channels.SocketChannel
import java.nio.channels.SelectionKey._
import java.nio.ByteBuffer
import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.NonFatal
import scala.concurrent.duration._
import akka.actor._
import akka.util.ByteString
import akka.io.Inet.SocketOption
import akka.io.Tcp._
import akka.io.SelectionHandler._

/**
 * Base class for TcpIncomingConnection and TcpOutgoingConnection.
 *
 * INTERNAL API
 */
private[io] abstract class TcpConnection(val tcp: TcpExt, val channel: SocketChannel) extends Actor with ActorLogging {
  import tcp.Settings._
  import tcp.bufferPool
  import TcpConnection._
  var pendingWrite: PendingWrite = null

  // Needed to send the ConnectionClosed message in the postStop handler.
  var closedMessage: CloseInformation = null

  private[this] var peerClosed = false
  private[this] var keepOpenOnPeerClosed = false

  def writePending = pendingWrite ne null

  // STATES

  /** connection established, waiting for registration from user handler */
  def waitingForRegistration(registration: ChannelRegistration, commander: ActorRef): Receive = {
    case Register(handler, keepOpenOnPeerClosed) ⇒
      // up to this point we've been watching the commander,
      // but since registration is now complete we only need to watch the handler from here on
      if (handler != commander) {
        context.unwatch(commander)
        context.watch(handler)
      }
      if (TraceLogging) log.debug("[{}] registered as connection handler", handler)
      this.keepOpenOnPeerClosed = keepOpenOnPeerClosed

      doRead(registration, handler, None) // immediately try reading
      context.setReceiveTimeout(Duration.Undefined)
      context.become(connected(registration, handler))

    case cmd: CloseCommand ⇒
      handleClose(registration, commander, Some(sender), cmd.event)

    case ReceiveTimeout ⇒
      // after sending `Register` user should watch this actor to make sure
      // it didn't die because of the timeout
      log.warning("Configured registration timeout of [{}] expired, stopping", RegisterTimeout)
      context.stop(self)
  }

  /** normal connected state */
  def connected(registration: ChannelRegistration, handler: ActorRef): Receive =
    handleWriteMessages(registration, handler) orElse {
      case StopReading           ⇒ registration.disableInterest(OP_READ)
      case ResumeReading         ⇒ registration.enableInterest(OP_READ)
      case ChannelReadable       ⇒ doRead(registration, handler, None)
      case cmd: CloseCommand     ⇒ handleClose(registration, handler, Some(sender), cmd.event)
      case Terminated(`handler`) ⇒ handlerTerminated()
    }

  /** the peer sent EOF first, but we may still want to send */
  def peerSentEOF(registration: ChannelRegistration, handler: ActorRef): Receive =
    handleWriteMessages(registration, handler) orElse {
      case cmd: CloseCommand     ⇒ handleClose(registration, handler, Some(sender), cmd.event)
      case Terminated(`handler`) ⇒ handlerTerminated()
    }

  /** connection is closing but a write has to be finished first */
  def closingWithPendingWrite(registration: ChannelRegistration, handler: ActorRef, closeCommander: Option[ActorRef],
                              closedEvent: ConnectionClosed): Receive = {
    case StopReading     ⇒ registration.disableInterest(OP_READ)
    case ResumeReading   ⇒ registration.enableInterest(OP_READ)
    case ChannelReadable ⇒ doRead(registration, handler, closeCommander)

    case ChannelWritable ⇒
      doWrite(registration, handler)
      if (!writePending) // writing is now finished
        handleClose(registration, handler, closeCommander, closedEvent)

    case Abort                 ⇒ handleClose(registration, handler, Some(sender), Aborted)

    case Terminated(`handler`) ⇒ handlerTerminated()
  }

  /** connection is closed on our side and we're waiting from confirmation from the other side */
  def closing(registration: ChannelRegistration, handler: ActorRef, closeCommander: Option[ActorRef]): Receive = {
    case StopReading           ⇒ registration.disableInterest(OP_READ)
    case ResumeReading         ⇒ registration.enableInterest(OP_READ)
    case ChannelReadable       ⇒ doRead(registration, handler, closeCommander)
    case Abort                 ⇒ handleClose(registration, handler, Some(sender), Aborted)
    case Terminated(`handler`) ⇒ handlerTerminated()
  }

  def handleWriteMessages(registration: ChannelRegistration, handler: ActorRef): Receive = {
    case ChannelWritable ⇒ if (writePending) doWrite(registration, handler)

    case write: Write if writePending ⇒
      if (TraceLogging) log.debug("Dropping write because queue is full")
      sender ! write.failureMessage

    case write: Write if write.data.isEmpty ⇒
      if (write.wantsAck)
        sender ! write.ack

    case write: Write ⇒
      pendingWrite = createWrite(write)
      doWrite(registration, handler)
  }

  // AUXILIARIES and IMPLEMENTATION

  def handlerTerminated(): Unit = {
    log.debug("Closing connection (stopping self) because handler terminated")
    closedMessage = null
    context.stop(self)
  }

  /** used in subclasses to start the common machinery above once a channel is connected */
  def completeConnect(registration: ChannelRegistration, commander: ActorRef,
                      options: immutable.Traversable[SocketOption]): Unit = {
    // Turn off Nagle's algorithm by default
    channel.socket.setTcpNoDelay(true)
    options.foreach(_.afterConnect(channel.socket))

    commander ! Connected(
      channel.socket.getRemoteSocketAddress.asInstanceOf[InetSocketAddress],
      channel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress])

    context.setReceiveTimeout(RegisterTimeout)
    context.become(waitingForRegistration(registration, commander))
  }

  def doRead(registration: ChannelRegistration, handler: ActorRef, closeCommander: Option[ActorRef]): Unit = {
    @tailrec def innerRead(buffer: ByteBuffer, remainingLimit: Int): ReadResult =
      if (remainingLimit > 0) {
        // never read more than the configured limit
        buffer.clear()
        val maxBufferSpace = math.min(DirectBufferSize, remainingLimit)
        buffer.limit(maxBufferSpace)
        val readBytes = channel.read(buffer)
        buffer.flip()

        if (TraceLogging) log.debug("Read [{}] bytes.", readBytes)
        if (readBytes > 0) handler ! Received(ByteString(buffer))

        readBytes match {
          case `maxBufferSpace` ⇒ innerRead(buffer, remainingLimit - maxBufferSpace)
          case x if x >= 0      ⇒ AllRead
          case -1               ⇒ EndOfStream
          case _ ⇒
            throw new IllegalStateException("Unexpected value returned from read: " + readBytes)
        }
      } else MoreDataWaiting

    val buffer = bufferPool.acquire()
    try innerRead(buffer, ReceivedMessageSizeLimit) match {
      case AllRead         ⇒ registration.enableInterest(OP_READ)
      case MoreDataWaiting ⇒ self ! ChannelReadable
      case EndOfStream if channel.socket.isOutputShutdown ⇒
        if (TraceLogging) log.debug("Read returned end-of-stream, our side already closed")
        doCloseConnection(handler, closeCommander, ConfirmedClosed)
      case EndOfStream ⇒
        if (TraceLogging) log.debug("Read returned end-of-stream, our side not yet closed")
        handleClose(registration, handler, closeCommander, PeerClosed)
    } catch {
      case e: IOException ⇒ handleError(handler, e)
    } finally bufferPool.release(buffer)
  }

  final def doWrite(registration: ChannelRegistration, handler: ActorRef): Unit = {
    @tailrec def innerWrite(): Unit = {
      val toWrite = pendingWrite.buffer.remaining()
      require(toWrite != 0)
      val writtenBytes = channel.write(pendingWrite.buffer)
      if (TraceLogging) log.debug("Wrote [{}] bytes to channel", writtenBytes)

      pendingWrite = pendingWrite.consume(writtenBytes)

      if (pendingWrite.hasData)
        if (writtenBytes == toWrite) innerWrite() // wrote complete buffer, try again now
        else registration.enableInterest(OP_WRITE) // try again later
      else { // everything written
        if (pendingWrite.wantsAck)
          pendingWrite.commander ! pendingWrite.ack

        val buffer = pendingWrite.buffer
        pendingWrite = null

        bufferPool.release(buffer)
      }
    }

    try innerWrite()
    catch { case e: IOException ⇒ handleError(handler, e) }
  }

  def closeReason =
    if (channel.socket.isOutputShutdown) ConfirmedClosed
    else PeerClosed

  def handleClose(registration: ChannelRegistration, handler: ActorRef, closeCommander: Option[ActorRef],
                  closedEvent: ConnectionClosed): Unit = closedEvent match {
    case Aborted ⇒
      if (TraceLogging) log.debug("Got Abort command. RESETing connection.")
      doCloseConnection(handler, closeCommander, closedEvent)
    case PeerClosed if keepOpenOnPeerClosed ⇒
      // report that peer closed the connection
      handler ! PeerClosed
      // used to check if peer already closed its side later
      peerClosed = true
      context.become(peerSentEOF(registration, handler))
    case _ if writePending ⇒ // finish writing first
      if (TraceLogging) log.debug("Got Close command but write is still pending.")
      context.become(closingWithPendingWrite(registration, handler, closeCommander, closedEvent))
    case ConfirmedClosed ⇒ // shutdown output and wait for confirmation
      if (TraceLogging) log.debug("Got ConfirmedClose command, sending FIN.")
      channel.socket.shutdownOutput()

      if (peerClosed) // if peer closed first, the socket is now fully closed
        doCloseConnection(handler, closeCommander, closedEvent)
      else context.become(closing(registration, handler, closeCommander))
    case _ ⇒ // close now
      if (TraceLogging) log.debug("Got Close command, closing connection.")
      doCloseConnection(handler, closeCommander, closedEvent)
  }

  def doCloseConnection(handler: ActorRef, closeCommander: Option[ActorRef], closedEvent: ConnectionClosed): Unit = {
    if (closedEvent == Aborted) abort()
    else channel.close()

    closedMessage = CloseInformation(Set(handler) ++ closeCommander, closedEvent)

    context.stop(self)
  }

  def handleError(handler: ActorRef, exception: IOException): Unit = {
    closedMessage = CloseInformation(Set(handler), ErrorClosed(extractMsg(exception)))
    throw exception
  }

  @tailrec private[this] def extractMsg(t: Throwable): String =
    if (t == null) "unknown"
    else {
      t.getMessage match {
        case null | "" ⇒ extractMsg(t.getCause)
        case msg       ⇒ msg
      }
    }

  def abort(): Unit = {
    try channel.socket.setSoLinger(true, 0) // causes the following close() to send TCP RST
    catch {
      case NonFatal(e) ⇒
        // setSoLinger can fail due to http://bugs.sun.com/view_bug.do?bug_id=6799574
        // (also affected: OS/X Java 1.6.0_37)
        if (TraceLogging) log.debug("setSoLinger(true, 0) failed with [{}]", e)
    }
    channel.close()
  }

  override def postStop(): Unit = {
    if (channel.isOpen)
      abort()

    if (writePending)
      bufferPool.release(pendingWrite.buffer)

    if (closedMessage != null) {
      val interestedInClose =
        if (writePending) closedMessage.notificationsTo + pendingWrite.commander
        else closedMessage.notificationsTo

      interestedInClose.foreach(_ ! closedMessage.closedEvent)
    }
  }

  override def postRestart(reason: Throwable): Unit =
    throw new IllegalStateException("Restarting not supported for connection actors.")

  private[io] case class PendingWrite(
      commander: ActorRef,
      ack: Any,
      remainingData: ByteString,
      buffer: ByteBuffer) {

    def consume(writtenBytes: Int): PendingWrite =
      if (buffer.remaining() == 0) {
        buffer.clear()
        val copied = remainingData.copyToBuffer(buffer)
        buffer.flip()
        copy(remainingData = remainingData.drop(copied))
      } else this

    def hasData = buffer.remaining() > 0 || remainingData.size > 0
    def wantsAck = !ack.isInstanceOf[NoAck]
  }
  def createWrite(write: Write): PendingWrite = {
    val buffer = bufferPool.acquire()
    val copied = write.data.copyToBuffer(buffer)
    buffer.flip()

    PendingWrite(sender, write.ack, write.data.drop(copied), buffer)
  }
}

/**
 * INTERNAL API
 */
private[io] object TcpConnection {
  sealed trait ReadResult
  object EndOfStream extends ReadResult
  object AllRead extends ReadResult
  object MoreDataWaiting extends ReadResult

  /**
   * Used to transport information to the postStop method to notify
   * interested party about a connection close.
   */
  case class CloseInformation(
    notificationsTo: Set[ActorRef],
    closedEvent: Event)
}
