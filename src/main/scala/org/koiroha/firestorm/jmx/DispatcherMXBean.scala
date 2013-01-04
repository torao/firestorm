/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm.jmx

import javax.management.{ObjectName, MXBean}
import org.koiroha.firestorm.{EventLog, Endpoint, DispatcherListener, Dispatcher}
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong
import java.nio.channels.SocketChannel

@MXBean
trait DispatcherMXBean {
	def getChannelCount():Int
	def getSelectPerSecond():Double
	def getAcceptConnectionPerSecond():Double
	def getReadTransmitPerSecond():Double
	def getWriteTransmitPerSecond():Double

	def shutdown():Unit
}

class DispatcherMXBeanImpl(dispatcher:Dispatcher) extends DispatcherMXBean with Monitor {
	private[this] val server = ManagementFactory.getPlatformMBeanServer
	private[this] val name = uniqueName(dispatcher.id)
	private[this] val select = new History()
	private[this] val accept = new History()
	private[this] val read = new History()
	private[this] val write = new History()
	private[this] var clientCount = 0
	private[this] val listener = new DispatcherListener(){
		override def onAccept(channel:SocketChannel):Unit = {
			select.current.value += 1
			accept.current.value += 1
		}
		override def onRead[T](ep:Endpoint[T], length:Int){
			select.current.value += 1
			read.current.value += length
		}
		override def onWrite[T](ep:Endpoint[T], length:Int){
			select.current.value += 1
			write.current.value += length
		}
		override def onOpen[T](endpoint:Endpoint[T]){
			clientCount += 1
		}
		override def onClosed[T](endpoint:Endpoint[T]){
			clientCount -= 1
		}
		override def onShutdown(){
			Heartbeat -= DispatcherMXBeanImpl.this
			server.unregisterMBean(name)
			EventLog.debug("unregister server mxbean of \"%s\" at %s".format(dispatcher.id, name))
		}
	}

	dispatcher += listener
	EventLog.debug("register dispatcher mxbean of \"%s\" at %s".format(dispatcher.id, name))
	server.registerMBean(this, name)
	Heartbeat += this

	def touch():Unit = {
		accept.touch()
		read.touch()
		write.touch()
	}

	def getSelectPerSecond():Double = select.average(2)
	def getAcceptConnectionPerSecond():Double = accept.average(2)
	def getReadTransmitPerSecond():Double = read.average(2)
	def getWriteTransmitPerSecond():Double = write.average(2)

	def getChannelCount():Int = clientCount

	def shutdown():Unit = {
		// jvm exiting at shutdown if there are no normal threads
		new Thread("FirestormReboot"){
			{ setDaemon(false) }
			override def run(){
				EventLog.warn("\"%s\" server shutting-down by JMX request".format(dispatcher.id))
				dispatcher.shutdown(10 * 1000)
			}
		}.start()
	}

	private[this] def uniqueName(id:String):ObjectName = {
		val f = "org.koiroha.firestorm:type=Dispatcher,name=%s"
		var n = new ObjectName(f.format(id))
		while(server.isRegistered(n)){
			n = new ObjectName(f.format(id + "@" + DispatcherMXBeanImpl.uniqueSequence.addAndGet(1)))
		}
		n
	}

}

object DispatcherMXBeanImpl {
	/** Unique sequence generator */
	private[jmx] val uniqueSequence = new AtomicLong(0)
}
