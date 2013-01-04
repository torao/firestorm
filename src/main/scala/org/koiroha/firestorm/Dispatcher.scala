/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm

import java.nio.channels._
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import scala.collection.JavaConversions._
import org.koiroha.firestorm.jmx.DispatcherMXBeanImpl
import java.net.{ServerSocket, Socket}

/**
 * `Dispatcher` は非同期 Socket I/O を行います。
 *
 * @param id このディスパッチャーの ID (JMX 名に使用)
 */
class Dispatcher(val id:String, readBufferSize:Int = 8 * 1024, var maxIdleInMillis:Long = Long.MaxValue){

	/**
	 * select() から各 Channel の入出力処理を行うスレッドです。
	 */
	private[this] var dispatcher:Option[Thread] = None

	/**
	 * ディスパッチャースレッド内で行う処理のキューです。
	 */
	private[this] val queue = new ArrayBlockingQueue[()=>Unit](10)

	/**
	 * このディスパッチャーが使用する `Selector`。ディスパッチャースレッドが停止しても shutdown が行われるまで
	 * クローズされない
	 */
	private[this] val selector = Selector.open()

	/**
	 * すべての Socket で共有する内部的な読み込みバッファ。ディスパッチャースレッド内でのみ使用するためスレッド間で
	 * 共有されない。
	 */
	private[this] val readBuffer = ByteBuffer.allocateDirect(readBufferSize)

	/**
	 * このディスパッチャーを監視しているリスナ。
	 */
	private[this] var listeners = List[DispatcherListener]()

	/**
	 * このディスパッチャーを JMX で監視する MXBean。
	 */
	private[this] val mxbean = new DispatcherMXBeanImpl(this)

	/**
	 * 接続済みの Socket チャネルをこのディスパッチャーにバインドします。
	 * @param channel バインドするチャネル
	 * @return 接続したエンドポイント
	 */
	private[firestorm] def bind[T](channel:SocketChannel, endpoint:Endpoint[T]) = {
		execInDispatcherThread{ () => openSelectionKey(channel, endpoint) }
	}

	/**
	 * 接続済みの ServerSocket チャネルをこのディスパッチャーにバインドします。
	 * @param channel バインドするチャネル
	 */
	private[firestorm] def bind[T](channel:ServerSocketChannel, server:Server[T]):SelectionKey = {
		execInDispatcherThread { () =>
			channel.configureBlocking(false)
			channel.register(selector, SelectionKey.OP_ACCEPT, server)
		}
	}

	def +=(l:DispatcherListener):Unit = listeners ::= l
	def -=(l:DispatcherListener):Unit = listeners = listeners.filter{ _ != l }
	private[firestorm] def eachListener(f:(DispatcherListener)=>Unit){
		listeners.foreach { f }
	}

	/**
	 * このディスパッチャーが使用しているすべての Socket をクローズし処理を終了します。
	 * @param gracefulCloseInMillis gracefully close in milliseconds
	 */
	def shutdown(gracefulCloseInMillis:Long):Unit = {

		// JavaVM 上にデーモンスレッドしかない場合 shutdown ディスパッチャースレッドが停止する同時に JavaVM が
		// 終了するため警告
		if(Thread.currentThread().isDaemon){
			EventLog.warn("shutdown is processing in daemon thread; note that jvm will exit immediately" +
				" in case it becomes only daemon threads by the end of this server")
		}

		execInDispatcherThread { () =>

		// ServerSocket をクローズして新しい接続の受け付けを停止
			selector.keys().filter{ _.channel().isInstanceOf[ServerSocketChannel] }.foreach { key =>
				key.cancel()
				EventLog.debug("closing server channel %s on port %d".format(
					key.channel().asInstanceOf[ServerSocketChannel].socket().getInetAddress.getHostAddress,
					key.channel().asInstanceOf[ServerSocketChannel].socket().getLocalPort
				))
				key.channel().close()
			}

			// すべての Endpoint に対してクローズ要求を実行
			selector.keys().filter{ _.attachment().isInstanceOf[Endpoint[_]] }.map{ _.attachment().asInstanceOf[Endpoint[_]] }.foreach { e =>
				e.onShutdown()
				e.close()
				listeners.foreach { _.onClosing(e) }
			}

			// すべての SelectionKey が取り除かれるまで待機
			val start = System.nanoTime()
			while((System.nanoTime() - start) / 1000 / 1000 < gracefulCloseInMillis && selector.keys().size() > 0){
				Thread.sleep(300)
			}

			// 残っているすべてのチャネルを強制的にクローズ
			selector.keys().foreach { key =>
				EventLog.warn("unclosed selection-key remaining: %s".format(key))
				closeSelectionKey(key)
			}

			// ディスパッチャースレッドに割り込みを行いスレッドを停止
			Thread.currentThread().interrupt()
			selector.wakeup()
		}

		// Selector をクローズ
		selector.close()

		// すべてのリスナに shutdown を通知
		listeners.foreach { _.onShutdown() }
	}

	/**
	 * 指定された処理をディスパッチャースレッド内で実行します。
	 * @param job ディスパッチャースレッド内で行う処理
	 */
	private[this] def asyncExecInDispatcherThread(job:()=>Unit):Unit = queue.synchronized {

		// Selector がクローズ状態ならすでに shutdown 済み
		if(! selector.isOpen){
			throw new IllegalStateException("dispatcher already shutdown")
		}

		// ディスパッチャースレッドが開始していなければ開始する
		if(! dispatcher.isDefined){
			val thread = new Thread(){
				override def run():Unit = dispatch()
			}
			thread.setName("Firestorm I/O Dispatcher [%s]".format(id))
			thread.setDaemon(false)
			thread.start()
			dispatcher = Some(thread)
		}

		// キューに処理を投入
		queue.put(job)

		// select() で停止している処理を起動
		selector.wakeup()
	}

	/**
	 * 指定された処理をディスパッチャースレッド内で実行します。
	 * @param job ディスパッチャースレッド内で行う処理
	 */
	private[this] def execInDispatcherThread[T](job:()=>T):T = {
		val signal = new Object()
		var result = List[T]()
		signal.synchronized {
			asyncExecInDispatcherThread { () =>
				signal.synchronized {
					result ::= job()
					signal.notify()
				}
			}
			signal.wait()
		}
		result(0)
	}

	/**
	 * ディスパッチ処理を開始します。
	 */
	private[this] def dispatch():Unit = try {

		// loop while reject ServerSocketChannel from selector
		var idleStartTime:Option[Long] = None
		while (! Thread.currentThread().isInterrupted) {

			// キューに投入されている処理を実行
			while(! queue.isEmpty){
				queue.take()()
			}

			// 入出力可能になった Socket の処理を実行
			if(selector.select(1000) > 0){
				val selectedKeys = selector.selectedKeys()
				// ※ループ内の remove() により ConcurrentModificationException が発生するため toList している
				selectedKeys.toList.foreach {
					key =>
						selectedKeys.remove(key)
						selected(key)
				}
				idleStartTime = None
			} else if (selector.keys().isEmpty){
				// Selector の処理する SelectionKey がなくなって一定時間経過したら Selector や Channel をそのままで
				// スレッドのみを終了
				idleStartTime match {
					case Some(t) =>
						if((System.nanoTime() - t) / 1000 / 1000 > maxIdleInMillis){
							Thread.currentThread().interrupt()
						}
					case None =>
						idleStartTime = Some(System.nanoTime())
				}
			} else {
				idleStartTime = None
			}
		}
	} catch {
		case ex:Throwable =>
			if (ex.isInstanceOf[ThreadDeath]){
				throw ex
			}
			EventLog.fatal(ex, "")
	} finally {

		// スレッド終了
		queue.synchronized {
			assert(dispatcher.get == Thread.currentThread())
			dispatcher = None
		}
	}

	/**
	 * 単一の SelectionKey を処理します。
	 * @param key 入出力可能になった SelectionKey
	 */
	private[this] def selected(key:SelectionKey):Unit = {
		// *** catch exception and close individual connection only read or write
		try {

			// read from channel
			if (key.isReadable) {
				if(! key.in.internalRead(readBuffer)){
					EventLog.debug("connection reset by peer: %s".format(sk2endpoint(key)))
					closeSelectionKey(key)
				}
				return
			}

			// write to channel
			if (key.isWritable) {
				key.out.internalWrite()
				return
			}

		} catch {
			case ex:Throwable =>
				if (ex.isInstanceOf[ThreadDeath]){
					throw ex
				}
				listeners.foreach { _.onError(key.attachment().asInstanceOf[Endpoint[_]], ex) }
				closeSelectionKey(key)
				return
		}

		// accept new connection (server behaviour)
		if (key.isAcceptable) {
			val server = key.channel().asInstanceOf[ServerSocketChannel]
			val client = server.accept()
			listeners.foreach { _.onAccept(client) }
			val endpoint = Endpoint[Any](this)
			openSelectionKey(client, endpoint)
			key.onAccept(endpoint)

			// 初期状態で書き込みバッファは空だが初回に onDepartureBufferedOut を呼び出すために OP_WRITE を指定
			endpoint.out.andThen{ None }
		}
	}

	private[this] implicit def sk2endpoint(key:SelectionKey):Endpoint[_] = key.attachment().asInstanceOf[Endpoint[_]]
	private[this] implicit def sk2server(key:SelectionKey):Server[Any] = key.attachment().asInstanceOf[Server[Any]]
	private[this] implicit def sk2socket(key:SelectionKey):Socket = key.channel().asInstanceOf[SocketChannel].socket()
	private[this] implicit def sk2ssocket(key:SelectionKey):ServerSocket = key.channel().asInstanceOf[ServerSocketChannel].socket()
	private[this] implicit def sk2schannel(key:SelectionKey):ServerSocketChannel = key.channel.asInstanceOf[ServerSocketChannel]

	/**
	 * Join new client channel to this dispatcher.
	 * @param channel new channel
	 */
	private[this] def openSelectionKey[T](channel:SocketChannel, endpoint:Endpoint[T]):Unit = {
		channel.configureBlocking(false)
		val selectionKey = channel.register(selector, SelectionKey.OP_READ, endpoint)
		endpoint.accept(selectionKey)
		listeners.foreach { _.onOpen(endpoint) }

		//
		endpoint.out.andThen { None }
	}

	/**
	 * Detach specified selection key and close channel. This method is for client channel only.
	 * @param key selection key to close
	 */
	private[this] def closeSelectionKey(key:SelectionKey){
		key.cancel()
		key.channel().close()
		if(key.attachment().isInstanceOf[Endpoint[_]]){
			listeners.foreach { _.onClosing(key.attachment().asInstanceOf[Endpoint[Any]]) }
		}
	}

	this += new DispatcherListener {
		override def onStartup():Unit = EventLog.info("[%s] startup dispatcher".format(id))
		override def onShutdown():Unit = EventLog.info("[%s] shutdown dispatcher".format(id))
		override def onError(ex:Throwable):Unit = EventLog.fatal(ex, "")
		override def onError[T](endpoint:Endpoint[T], ex:Throwable):Unit
			= EventLog.error(ex, "[%s] uncaught exception in endpoint %s".format(id, endpoint))

		override def onAccept(channel:SocketChannel):Unit = {
			val socket = channel.socket()
			EventLog.debug("[%s] accepts new connection from %s on port %d".format(
				id, socket.getInetAddress.getHostAddress, socket.getPort))
		}
		override def onOpen[T](endpoint:Endpoint[T]):Unit = { }
		override def onClosing[T](endpoint:Endpoint[T]):Unit = { }
		override def onClosed[T](endpoint:Endpoint[T]):Unit = { }
		override def onRead[T](endpoint:Endpoint[T], length:Int):Unit = { }
		override def onWrite[T](endpoint:Endpoint[T], length:Int):Unit = { }

	}

}

trait DispatcherListener {

	def onStartup():Unit = { }

	def onShutdown():Unit = { }

	def onError(ex:Throwable):Unit = { }

	def onAccept(channel:SocketChannel):Unit = { }

	def onOpen[T](endpoint:Endpoint[T]):Unit = { }

	def onClosing[T](endpoint:Endpoint[T]):Unit = { }

	def onClosed[T](endpoint:Endpoint[T]):Unit = { }

	def onRead[T](endpoint:Endpoint[T], length:Int):Unit = { }

	def onWrite[T](endpoint:Endpoint[T], length:Int):Unit = { }

	def onError[T](endpoint:Endpoint[T], ex:Throwable):Unit = { }
}
