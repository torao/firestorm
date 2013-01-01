/*
 * Copyright (c) 2012 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm

import java.text.SimpleDateFormat
import java.util.Date
import java.util.logging._

object EventLog {
	val FATAL = 0
	val ERROR = 1
	val WARN = 2
	val INFO = 3
	val DEBUG = 4
	val TRACE = 5

	private[this] val logger = Logger.getLogger("org.koiroha.firestorm")
	private[this] val handler = new ConsoleHandler()
	logger.addHandler(handler)
	handler.setLevel(Level.FINEST)
	handler.setFormatter(new Formatter {
		def format(record:LogRecord):String = {
			val tm = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(record.getMillis))
			"[%s] %s%n".format(tm, record.getMessage)
		}
	})

	def fatal(message: => String) = fatal(null, message)
	def fatal(ex:Throwable, message: => String) = log(FATAL, message, ex)
	def error(message: => String) = error(null, message)
	def error(ex:Throwable, message: => String) = log(ERROR, message, ex)
	def warn(message: => String) = warn(null, message)
	def warn(ex:Throwable, message: => String) = log(WARN, message, ex)
	def info(message: => String) = info(null, message)
	def info(ex:Throwable, message: => String) = log(INFO, message, ex)
	def debug(message: => String) = debug(null, message)
	def debug(ex:Throwable, message: => String) = log(DEBUG, message, ex)
	def trace(message: => String) = trace(null, message)
	def trace(ex:Throwable, message: => String) = log(TRACE, message, ex)

	private[this] def log(level:Int, message: =>String, ex:Throwable = null){
		val l = level match {
			case FATAL => Level.SEVERE
			case ERROR => Level.SEVERE
			case WARN => Level.WARNING
			case INFO => Level.INFO
			case DEBUG => Level.FINE
			case TRACE => Level.FINEST
		}
		if(ex == null){
			logger.log(l, msg)
		} else {
			logger.log(l, msg, ex)
		}
	}

}
