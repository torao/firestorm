/*
 * Copyright (c) 2012 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm.server

trait Session {

	def consume(f:(Array[Byte],Int,Int)=>Int):Unit

	def write(buffer:Array[Byte]):Unit = write(buffer, 0, buffer.length)

	def write(buffer:Array[Byte], offset:Int, length:Int)

	def close():Unit
}
