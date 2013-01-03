/*
 * Copyright (c) 2012 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * org.koiroha.firestorm.http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm

import java.nio.ByteBuffer
import java.nio.channels.{SocketChannel, SelectionKey}
import java.util.concurrent.LinkedBlockingQueue
import java.io.IOException

/**
 * Endpoint that represents TCP/IP socket.
 * @tparam T object type to bind this session
 */
trait Endpoint[T] {

	/**
	 * Any of protocol-specified value that is associated with this endpoint scope. Default is `None`.
	 */
	var session:Option[T]

	/**
	 * Read buffer of this endpoint.
	 */
	val in:ReadableStreamBuffer

	/**
	 * Write buffer of this endpoint.
	 */
	val out:WritableStreamBuffer

	/**
	 * Flush all pending write buffer and close endpoint.
	 */
	def close():Unit

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
