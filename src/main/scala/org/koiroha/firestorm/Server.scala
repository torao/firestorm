/*
 * Copyright (c) 2012 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * org.koiroha.firestorm.http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm

import org.koiroha.firestorm.jmx.ServerMXBeanImpl
import java.nio.channels.SocketChannel

/**
 * Generic asynchronous server.
 */
class Server[T](config:ServerConfig, protocol:Protocol[T], mxbean:Option[ServerMXBeanImpl]){

	val id = config.id

	/**
	 * Server connection state that have ServerSocket.
	 */
	var dispatcher:Option[Dispatcher[T]] = None

	val started = new Object()

	/**
	 * Startup this server and wait to be acceptable connections if it didn't start yet.
	 */
	def startup():Unit = synchronized{
		if(! dispatcher.isDefined){
			EventLog.info("starting \"%s\" server at %s on port %d".format(
				config.id, config.address.getAddress.getHostAddress, config.address.getPort))

			val d = new ServerDispatcher()
			d.add(config.createServerChannel())

			started.synchronized{
				d.start()
				started.wait()
			}
			dispatcher = Some(d)
		}
	}

	/**
	 * Shutdown server.
	 * @param gracefulWaitInMillis
	 */
	def shutdown(gracefulWaitInMillis:Long):Unit = synchronized{
		dispatcher.foreach { d =>
			try {
				d.shutdown(gracefulWaitInMillis)
				EventLog.info("shutdown \"%s\" server at %s on port %d".format(
					id, config.address.getAddress.getHostAddress, config.address.getPort))
			} catch {
				case ex:Exception => ex.printStackTrace()
			}
		}
		dispatcher = None
	}

	/**
	 * Reboot this server. This method is utility of shutdown-startup sequence.
	 */
	def reboot(gracefulShutdownInMillis:Long):Unit = synchronized{
		shutdown(gracefulShutdownInMillis)
		startup()
	}

	private[firestorm] class ServerDispatcher extends Dispatcher[T](config.id, config.readBufferSize) {

		override def onStartup():Unit = {
			EventLog.debug("starting server thread: \"%s\"".format(id))
			started.synchronized {
				started.notify()
			}
			mxbean.foreach { _.start(Server.this) }
		}

		override def onShutdown():Unit = {
			mxbean.foreach { _.stop() }
			EventLog.debug("finishing server thread: \"%s\"".format(id))
		}

		override def onAccept(channel:SocketChannel):Unit = {
			config.initClientChannel(channel)
		}

		override def onOpen(endpoint:Endpoint[T]):Unit = {
			mxbean.foreach { _.addClientCount(1) }
			protocol.open(endpoint)
		}

		override def onClosing(endpoint:Endpoint[T]):Unit = {
			try{
				protocol.close(endpoint)
			} catch {
				case ex:Exception =>
					EventLog.warn(ex, "exception caught while shutting-down socket: " + protocol.name)
			}
		}

		override def onClosed(endpoint:Endpoint[T]):Unit = {
			mxbean.foreach { _.addClientCount(-1) }
		}

		override def onRead(endpoint:Endpoint[T], length:Int):Unit = {
			protocol.receive(endpoint)
			mxbean.foreach { _.addReadBytes(length) }
		}

		override def onWrite(endpoint:Endpoint[T], length:Int):Unit = {
			mxbean.foreach { _.addWriteBytes(length) }
		}
	}

}
