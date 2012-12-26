/*
 * Copyright (c) 2012 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */
package org.koiroha.firestorm


// ==========================================================================
// Firestorm:
// ==========================================================================
/**
 *
 */
object Firestorm {

	// ========================================================================
	// ========================================================================
	/**
	 *
	 */
	def main(args:Array[String]):Unit = {

		// parse commandline parameters
		val config = new Config().parseCommandlineParameters(args.toList)

	}

}


