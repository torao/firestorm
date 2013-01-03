/*
 * Copyright (c) 2012 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * org.koiroha.firestorm.http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm

import java.net.{InetAddress, InetSocketAddress, SocketAddress, ServerSocket}
import java.nio.channels.{SocketChannel, ServerSocketChannel}
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import org.koiroha.firestorm.jmx.ServerMXBeanImpl

/**
 * Server configuration.
 */
case class ServerConfig(
	id:String,
	channelFactory:()=>ServerSocketChannel,
	address:InetSocketAddress,
	readBufferSize:Int,
	readTimeout:Long,
	useJMX:Boolean
) {

	def this() = this(
		"",
		{ () => ServerSocketChannel.open() },
		new InetSocketAddress(9657),
		8 * 1024,
		-1,
		false
	)

	def bind(port:Int):ServerConfig = bind(new InetSocketAddress(port))
	def bind(address:InetAddress, port:Int):ServerConfig = bind(new InetSocketAddress(address, port))
	def bind(hostname:String, port:Int):ServerConfig = bind(new InetSocketAddress(hostname, port))
	def bind(address:InetSocketAddress):ServerConfig = {
		ServerConfig(id, channelFactory, address, readBufferSize, readTimeout, useJMX)
	}

	def withReadBufferSize(size:Int):ServerConfig = {
		ServerConfig(id, channelFactory, address, size, readTimeout, useJMX)
	}

	def withSocketFactory(factory:()=>ServerSocketChannel):ServerConfig = {
		ServerConfig(id, factory, address, readBufferSize, readTimeout, useJMX)
	}

	def withId(id:String):ServerConfig = {
		ServerConfig(id, channelFactory, address, readBufferSize, readTimeout, useJMX)
	}

	def withJMX(useJMX:Boolean):ServerConfig = {
		ServerConfig(id, channelFactory, address, readBufferSize, readTimeout, useJMX)
	}

	def bind[T](protocol:Protocol[T]):Server[T] = {
		val mxbean = if (useJMX){
			Some(new ServerMXBeanImpl(id))
		} else {
			None
		}
		new Server(this, protocol, mxbean)
	}

	/**
	 * Create new server-channel from socket factory.
	 * @return new server channel
	 */
	private[firestorm] def createServerChannel():ServerSocketChannel = {
		val channel = channelFactory()
		assert(channel != null, "ServerSocketChannel is null")
		channel.bind(address)
		channel
	}

	private[firestorm] def initClientChannel(channel:SocketChannel):Unit = {
		if(readTimeout >= 0){
			channel.socket().setSoTimeout((readTimeout & 0x7FFFFFFF).toInt)
		}
	}
}
