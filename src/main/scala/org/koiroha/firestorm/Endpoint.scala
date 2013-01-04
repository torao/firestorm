/*
 * Copyright (c) 2012 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * org.koiroha.firestorm.http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm

import java.nio.ByteBuffer
import java.nio.channels.{NetworkChannel, SocketChannel, SelectionKey}
import java.util.concurrent.LinkedBlockingQueue
import java.io.IOException
import java.net.{SocketAddress, InetSocketAddress}

/**
 * Endpoint that represents TCP/IP socket.
 */
case class Endpoint[T](dispatcher:Dispatcher){

	private[this] var selectionKey:Option[SelectionKey] = None

	var session:Option[T] = None

	override def toString():String = selectionKey match {
		case Some(key) =>
			val socket = key.channel().asInstanceOf[SocketChannel].socket()
			"%s at port %d".format(socket.getInetAddress.getHostAddress, socket.getPort)
		case None => "(disconnected)"
	}

	/**
	 * このディスパッチャーを使用して指定されたホストへ接続します。
	 * @param hostname 接続先のホスト名
	 * @param port 接続先のポート番号
	 * @return 接続したエンドポイント
	 */
	def connect(hostname:String, port:Int):Endpoint[T] = connect(new InetSocketAddress(hostname, port))

	/**
	 * このディスパッチャーを使用して指定されたアドレスへ接続します。
	 * @param address 接続先のアドレス
	 * @return 接続したエンドポイント
	 */
	def connect(address:SocketAddress):Endpoint[T] = {
		dispatcher.bind(SocketChannel.open(address), this)
		this
	}

	/**
	 */
	private[firestorm] def accept(key:SelectionKey):Endpoint[T] = {
		assert(selectionKey.isEmpty)
		selectionKey = Option(key)
		onConnect()
		this
	}

	/**
	 * I/O channel of this endpoint.
	 */
	private[this] def channel:SocketChannel = {
		selectionKey match {
			case Some(k) => k.channel().asInstanceOf[SocketChannel]
			case None => throw new IllegalStateException("not ")
		}
	}

	/**
	 * Finish all pending writing buffer and close this endpoint.
	 */
	def close():Unit = {
		EventLog.debug("closing connection by protocol")
		channel.shutdownInput()
		dispatcher.eachListener { _.onClosing(this) }
		out.close()
	}

	/**
	  */
	private[firestorm] def internalClose():Unit = {
		selectionKey.get.cancel()
		channel.close()
		dispatcher.eachListener { _.onClosed(this) }
		EventLog.debug("connection closed by protocol")
	}

	/**
	 * Read buffer of this endpoint.
	 */
	lazy val in = new ReadableStreamBuffer() {
		val initialSize:Int = 4 * 1024
		val factor:Double = 1.5

		/**
		 * Internal buffer that automatically expand. This is not overwritten for available data block
		 * because these are shared by chunked ByteBuffer.
		 */
		private[this] var buffer = new Array[Byte](initialSize)

		/**
		 * Available data length in `buffer` from `consumed`.
		 */
		var available:Int = 0

		/**
		 * Available data offset from head of `buffer`.
		 */
		var consumed:Int = 0

		/**
		 * Available data length contained in this steam buffer.
		 */
		def length:Int = available

		/**
		 * Read from channel by use of specified buffer and append internal buffer, notify to receive
		 * to protocol.
		 * @param buffer commonly used read buffer
		 * @return false if channel reaches end-of-stream
		 */
		def internalRead(buffer:ByteBuffer):Boolean = {
			val length = channel.read(buffer)
			if(length < 0){
				false
			} else {
				if(length > 0){
					buffer.flip()
					append(buffer, length)
					dispatcher.eachListener { _.onRead(Endpoint.this, length) }
					onArrivalBufferedIn()
					buffer.clear()
				}
				true
			}
		}

		def consume(trimmer:(Array[Byte], Int, Int)=>Int):Option[ByteBuffer] = synchronized{
			val len = trimmer(buffer, consumed, available)
			if(len <= 0 || len > available){
				None
			} else {
				val buf = ByteBuffer.wrap(buffer, consumed, len)
				consumed += len
				available -= len
				Some(buf)
			}
		}

		private[this] def append(buf:ByteBuffer, length:Int):Unit = synchronized{

			// expand internal buffer if remaining area is too small
			if(buffer.length - consumed - available < buf.remaining()){
				val newSize = math.max((available + buf.remaining()) * factor, initialSize).toInt
				val temp = new Array[Byte](newSize)
				System.arraycopy(buffer, consumed, temp, 0, available)
				buffer = temp
				consumed = 0
			}

			// append specified data to internal buffer
			buf.get(buffer, consumed + available, length)
			this.available += length
		}

	}

	lazy val out = new WritableStreamBuffer() {

		/**
		 * Flag to specify that write operation is invalid.
		 */
		private[this] var writeOperationClosed = false

		/**
		 * Queued buffer length.
		 */
		@volatile
		private[this] var length = 0

		/**
		 * Write pending queue that may contain ByteBuffers or other operations.
		 */
		private[this] val writeQueue = new LinkedBlockingQueue[()=>Boolean](Int.MaxValue)

		def andThen(callback: =>Unit):WritableStreamBuffer = writeQueue.synchronized {
			if(writeOperationClosed || ! selectionKey.isDefined){
				throw new IOException("channel closed")
			}
			post {() =>
				callback
				true
			}
			this
		}

		def write(buffer:ByteBuffer):WritableStreamBuffer = writeQueue.synchronized {
			if(writeOperationClosed || ! selectionKey.isDefined){
				throw new IOException("channel closed")
			}
			if(buffer.remaining() > 0){
				if(length + buffer.remaining() > maxWriteBufferSize){
					throw new IOException("write buffer overflow")
				}
				post {() =>
					val len = channel.write(buffer)
					length -= len
					dispatcher.eachListener { _.onWrite(Endpoint.this, len) }
					(buffer.remaining() == 0)
				}
				length += buffer.remaining()
			}
			this
		}

		def close():Unit = writeQueue.synchronized {
			writeOperationClosed = true
			post{ () => true }
		}

		private[this] def post(f:()=>Boolean){
			if(! writeQueue.offer(f)){
				throw new IOException("write queue overflow")
			}
			selectionKey.foreach { k => k.interestOps(k.interestOps() | SelectionKey.OP_WRITE) }
		}

		/**
		 * Write buffered data internally.
		 * This take job from queue and execute it
		 */
		def internalWrite():Unit = writeQueue.synchronized {
			val callback = writeQueue.peek()
			assert(callback != null)
			if(callback()){
				writeQueue.remove()
				if(writeQueue.isEmpty){
					if (writeOperationClosed){
						internalClose()
					} else {
						onDepartureBufferedOut()
						val key = selectionKey.get
						if(writeQueue.isEmpty && key.isValid){
							key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE)
						}
					}
				}
			}
		}
	}

	private[this] var connect:Option[(Endpoint[T])=>Unit] = None
	private[firestorm] def onConnect():Unit = connect.foreach { _(this) }
	def onConnect(f:(Endpoint[T])=>Unit):Endpoint[T] = {
		connect = Option(f)
		this
	}

	private[this] var arrivalBufferedIn:Option[(Endpoint[T])=>Unit] = None
	private[firestorm] def onArrivalBufferedIn():Unit = arrivalBufferedIn.foreach { _(this) }
	def onArrivalBufferedIn(f:(Endpoint[T])=>Unit):Endpoint[T] = {
		arrivalBufferedIn = Option(f)
		this
	}

	private[this] var departureBufferedOut:Option[(Endpoint[T])=>Unit] = None
	private[firestorm] def onDepartureBufferedOut():Unit = departureBufferedOut.foreach { _(this) }
	def onDepartureBufferedOut(f:(Endpoint[T])=>Unit):Endpoint[T] = {
		departureBufferedOut = Option(f)
		this
	}

	// Dispatcher#shutdown() により実行される処理。Endpoint へ終了データを書き込む必要がある。
	private[this] var shutdown:Option[(Endpoint[T])=>Unit] = None
	private[firestorm] def onShutdown():Unit = shutdown.foreach{ _(this) }
	def onShutdown(f:(Endpoint[T])=>Unit):Endpoint[T] = {
		shutdown = Option(f)
		this
	}

}

trait ReadableStreamBuffer {

	/**
	 * buffered read-data length.
	 */
	def length:Int

	/**
	 * Retrieve protocol-specified data from read buffer. The closure `trimmer` should scan buffer
	 * and return available data block length of buffer from offset, or zero if buffered data is not
	 * enough.
	 * @param trimmer
	 * @return Some(ByteBuffer) if closure return available length, or None if 0 returned
	 */
	def consume(trimmer:(Array[Byte], Int, Int)=>Int):Option[ByteBuffer]
}

trait WritableStreamBuffer {

	/**
	 * バッファリングされるデータの最大バイト数。出力バッファにこれより大きいデータを投入しようとした場合、
	 * `write()` 操作で `IOException` が発生します。
	 */
	var maxWriteBufferSize = 1 * 1024 * 1024

	def write(buffer:Array[Byte]):WritableStreamBuffer = {
		write(buffer, 0, buffer.length)
	}

	def write(buffer:Array[Byte], offset:Int, length:Int):WritableStreamBuffer = {
		val buf = ByteBuffer.allocate(length)
		buf.put(buffer, offset, length)
		buf.flip()
		write(buf)
	}

	/**
	 * Enqueue specified ByteBuffer to write. Note that modification for `buffer` after call effects
	 * write operation.
	 * This is asynchronous operation. If you want to know that buffer finish to write, you can use
	 * `andThen()` callback after `write()`.
	 * @param buffer
	 * @return
	 */
	def write(buffer:ByteBuffer):WritableStreamBuffer

	/**
	 * Enqueue specified callback closure.
	 * @param callback
	 * @return
	 */
	def andThen(callback: =>Unit):WritableStreamBuffer

}
