/*
 * Copyright (c) 2012 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm.server

trait Protocol {

	val name:String

	def open(session:Session):Unit

	def close(session:Session):Unit

	/**
	 * Shutting-down server and close specified session immediately.
	 * @param session session to close
	 */
	def shutdown(session:Session):Unit

	/**
	 * Call to receive data from remote passively.
	 * @param session
	 */
	def receive(session:Session):Unit

}
