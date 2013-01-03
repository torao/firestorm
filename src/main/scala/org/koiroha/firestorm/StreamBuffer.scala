/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm

import java.nio.ByteBuffer

class StreamBuffer(val initialSize:Int = 1024, val expansionLimit:Double = 10) {

	/**
	 * Internal buffer that automatically expand.
	 */
	private[this] var buffer = new Array[Byte](initialSize)

	/**
	 * Available data length in buffer.
	 */
	private[this] var length:Int = 0

	def size():Int = length

	def consume():ByteBuffer = synchronized{
		val buf = ByteBuffer.wrap(buffer, 0, length)
		length = 0
		if(buffer.length > initialSize * expansionLimit){
			buffer = new Array[Byte]((initialSize * expansionLimit).toInt)
		}
		buf
	}

	def append(buffer:Array[Byte], offset:Int, length:Int):Unit = synchronized{
		ensureCapacity(length)
		System.arraycopy(buffer, offset, this.buffer, this.length, length)
		this.length += length
	}

	def append(buf:ByteBuffer, length:Int):Unit = synchronized{
		ensureCapacity(length)
		buf.put(buffer, this.length, length)
		this.length += length
	}

	/**
	 * Expand internal buffer if necessary.
	 */
	private[this] def ensureCapacity(length:Int):Unit = {
		if (this.length + length > buffer.length){
			val temp = new Array[Byte](((buffer.length + length) * 1.2).toInt)
			System.arraycopy(buffer, 0, temp, 0, this.length)
			buffer = temp
		}
	}
}
