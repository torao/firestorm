/*
 * Copyright (c) 2012 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * org.koiroha.firestorm.http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm

import scala.annotation.tailrec

trait Protocol[T] {

	val name:String

	def open(endpoint:Endpoint[T]):Unit

	def close(endpoint:Endpoint[T]):Unit

	/**
	 * Call to receive data from remote passively.
	 * @param endpoint
	 */
	def receive(endpoint:Endpoint[T]):Unit

}

/**
 * Utility functions for protocol implementation.
 */
object Protocol {

	/**
	 * Find position in buffer offset from `offset` that matches to specified pattern string as UTF-8.
	 * @param buffer
	 * @param offset
	 * @param length
	 * @param patterns
	 * @return
	 */
	def findUTF8Sequence(buffer:Array[Byte], offset:Int, length:Int, patterns:String*):Option[(Int,String)] = {
		findBinarySequence(buffer, offset, length, patterns.map{ _.getBytes("UTF-8") }:_*) match {
			case Some((i, p)) => Some(i, new String(p, "UTF-8"))
			case None => None
		}
	}

	/**
	 * Find position in buffer offset from `offset` that matches to one of specified patterns.
	 * @param buffer
	 * @param offset
	 * @param length
	 * @param patterns
	 * @return
	 */
	def findBinarySequence(buffer:Array[Byte], offset:Int, length:Int, patterns:Array[Byte]*):Option[(Int,Array[Byte])] = {
		@tailrec
		lazy val f:(Int)=>Option[(Int,Array[Byte])] = { (o) =>
			if (o == length){
				None
			} else {
				patterns.find{ pattern => startsWith(buffer, offset + o, length - o, pattern) } match {
					case Some(p) => Some((o, p))
					case None => f(o + 1)
				}
			}
		}
		f(0)
	}

	/**
	 * Evaluate whether `buffer[offset]` starts with specified binary `pattern` or not.
	 * @param buffer binary buffer
	 * @param offset offset to evaluate pattern in buffer
	 * @param length
	 * @param pattern
	 * @return
	 */
	private[this] def startsWith(buffer:Array[Byte], offset:Int, length:Int, pattern:Array[Byte]):Boolean = {
		if (length >= pattern.length){
			@tailrec
			lazy val f:(Int)=>Boolean = { (o) =>
				if (o == pattern.length){
					true
				} else if (buffer(offset + o) != pattern(o)){
					false
				} else {
					f(o + 1)
				}
			}
			f(0)
		} else {
			false
		}
	}

}