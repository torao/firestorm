/*
 * Copyright (c) 2012 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha

package object firestorm {

	object Logger {
		def debug(msg:String){
		}
		def warn(msg:String, ex:Throwable){
			System.err.println(msg)
			ex.printStackTrace()
		}
	}

	implicit def obj2logger(obj:AnyRef):org.koiroha.firestorm.Logger = Logger

}
