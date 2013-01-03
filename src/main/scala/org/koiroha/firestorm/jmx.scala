/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm

import java.lang.management.ManagementFactory
import javax.management.{MXBean, ObjectName}
import java.util.{TimerTask, Timer}

package object jmx {

	private[this] var servers = List[ServerMXBeanImpl]()
	private[this] val timer = new Timer("FirestormJMXMonitor", true)
	timer.scheduleAtFixedRate(new TimerTask {
		def run() {
			servers.foreach { _.touch() }
		}
	}, 1000, 1000)

	@MXBean
	trait ServerMXBean {
		def getChannelCount():Int
		def getReadTransmitPerSecond():Double
		def getWriteTransmitPerSecond():Double

		def shutdown():Unit
		def reboot():Unit
	}

	class ServerMXBeanImpl(id:String) extends ServerMXBean{
		val server = ManagementFactory.getPlatformMBeanServer
		val name = new ObjectName("org.koiroha.firestorm:type=Server,name=" + id)
		var target:Option[Server[_]] = None

		def start[T](s:Server[T]):Unit = {
			target = Some(s)
			server.registerMBean(this, name)
			servers ::= this
			EventLog.debug("register server mxbean of \"%s\" at %s".format(id, name))
		}

		def stop():Unit = {
			server.unregisterMBean(name)
			servers = servers - this
			EventLog.debug("unregister mxbean")
			target = None
		}

		val read = new History()
		val write = new History()

		def touch():Unit = {
			read.touch()
			write.touch()
		}

		def addReadBytes(size:Int){
			read.current.value += size
		}
		def addWriteBytes(size:Int){
			write.current.value += size
		}

		var clientCount = 0
		def addClientCount(inc:Int){
			clientCount += inc
		}

		def getReadTransmitPerSecond():Double = read.average(2)

		def getWriteTransmitPerSecond():Double = write.average(2)

		def getChannelCount():Int = clientCount

		def shutdown():Unit = {
			target.foreach { s =>
				// jvm exiting at shutdown if there are no normal threads
				new Thread("FirestormReboot"){
					{ setDaemon(false) }
					override def run(){
						EventLog.warn("\"%s\" server shutting-down by JMX request".format(id))
						s.shutdown(5 * 1000)
					}
				}.start()
			}
		}

		def reboot():Unit = {
			target.foreach { s =>
				// jvm exiting at shutdown if there are no normal threads
				new Thread("FirestormReboot"){
					{ setDaemon(false) }
					override def run(){
						EventLog.warn("\"%s\" server rebooting by JMX request".format(id))
						s.reboot(5 * 1000)
					}
				}.start()
			}
		}

	}

	private[jmx] class History{
		case class Mark(tm:Long, var value:Long)
		val history = new Array[Mark](15 * 60)
		var current = Mark(System.nanoTime(), 0)
		history(0) = current

		def touch(){
			System.arraycopy(history, 0, history, 1, history.length - 1)
			current = Mark(System.nanoTime(), 0)
			history(0) = current
		}

		def average(seconds:Int):Double = {
			val hist = history.zipWithIndex.takeWhile{ case (mark, i) => mark != null && i < seconds }.map{ _._1 }
			val begin = hist(hist.size - 1).tm
			val end = System.nanoTime()
			val sum = hist.map { _.value }.sum
			if (begin >= end){
				Double.NaN
			} else {
				sum.toDouble / (end - begin) * 1000 * 1000 * 1000
			}
		}
	}

}