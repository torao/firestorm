/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm

import java.nio.channels.{ServerSocketChannel, SelectionKey}
import java.net.{SocketAddress, InetSocketAddress}

/**
 * 指定されたディスパッチャー上でサービスを行うサーバを構築します。
 * @param dispatcher ディスパッチャー
 */
case class Server[T](dispatcher:Dispatcher) {

	private[this] var selectionKey:Option[SelectionKey] = None
	private[this] var accept:Option[(Server[T], Endpoint[T])=>Unit] = None

	private[firestorm] def onAccept(endpoint:Endpoint[T]):Unit = {
		accept.foreach{ _(this, endpoint) }
	}

	/**
	 * サーバが接続を受け付けたときの処理を指定します。
	 * @param f Accept 時の処理
	 * @return
	 */
	def onAccept(f:(Server[T], Endpoint[T])=>Unit):Server[T] = {
		accept = Option(f)
		this
	}

	/**
	 * 指定されたポート上で Listen するサーバを構築します。
	 * @param port
	 */
	def listen(port:Int):Server[T] = listen(new InetSocketAddress(port), -1)

	/**
	 * 指定されたアドレス上で Listen するサーバを構築します。
	 * @param local
	 * @param backlog
	 */
	def listen(local:SocketAddress, backlog:Int):Server[T] = {
		val channel = ServerSocketChannel.open()
		channel.bind(local, backlog)
		selectionKey = Some(dispatcher.bind(channel, this))
		this
	}

}
