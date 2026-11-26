package com.mousemobile.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Сеть: управляющее соединение (команды мыши/клавиатуры), поток экрана и поиск ПК.
 * connect() и discover() блокирующие — вызывать только из фонового потока.
 */
object Net {
    private const val CTRL_PORT = 8765
    private const val SCREEN_PORT = 8766
    private const val DISCOVERY_PORT = 8767

    private val sender = Executors.newSingleThreadExecutor()

    @Volatile private var ctrl: Socket? = null
    @Volatile private var screen: Socket? = null
    @Volatile private var screenThread: Thread? = null
    @Volatile private var host = ""
    @Volatile private var pin = ""

    /** Вызывается из фонового потока, когда соединение оборвалось. */
    @Volatile var onLost: (() -> Unit)? = null

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 64) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    private fun handshake(s: Socket, code: String): String {
        val out = s.getOutputStream()
        out.write("AUTH $code\n".toByteArray(Charsets.UTF_8))
        out.flush()
        return readLine(s.getInputStream())
    }

    /** Возвращает null при успехе или текст ошибки. */
    fun connect(address: String, code: String): String? {
        disconnect()
        var s: Socket? = null
        try {
            s = Socket()
            s.connect(InetSocketAddress(address, CTRL_PORT), 3000)
            s.tcpNoDelay = true
            s.soTimeout = 5000
            val reply = handshake(s, code)
            if (reply != "OK") {
                s.close()
                return when (reply) {
                    "NO" -> "Неверный PIN"
                    "BAN" -> "Слишком много попыток. Подождите 5 минут."
                    else -> "Нет ответа от сервера"
                }
            }
            s.soTimeout = 0
            host = address
            pin = code
            ctrl = s
            return null
        } catch (e: Exception) {
            try {
                s?.close()
            } catch (e2: Exception) {
            }
            return "Не удалось подключиться: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** Отправка команды в фоне, чтобы не трогать сеть из главного потока. */
    fun send(line: String) {
        val s = ctrl ?: return
        sender.execute {
            try {
                val out = s.getOutputStream()
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (e: IOException) {
                if (ctrl === s) {
                    disconnect()
                    onLost?.invoke()
                }
            }
        }
    }

    fun disconnect() {
        stopScreen()
        val s = ctrl
        ctrl = null
        try {
            s?.close()
        } catch (e: Exception) {
        }
    }

    /** Запускает приём кадров экрана ПК. onFrame/onEnd вызываются из фонового потока. */
    fun startScreen(onFrame: (Bitmap) -> Unit, onEnd: (String) -> Unit) {
        stopScreen()
        val address = host
        val code = pin
        val t = Thread {
            val s = Socket()
            screen = s
            try {
                s.connect(InetSocketAddress(address, SCREEN_PORT), 3000)
                s.tcpNoDelay = true
                s.soTimeout = 8000
                if (handshake(s, code) != "OK") throw IOException("доступ запрещён")
                val input = DataInputStream(s.getInputStream().buffered())
                while (true) {
                    val n = input.readInt()
                    if (n <= 0 || n > 8_000_000) throw IOException("плохой кадр")
                    val buf = ByteArray(n)
                    input.readFully(buf)
                    val bmp = BitmapFactory.decodeByteArray(buf, 0, n)
                    if (bmp != null) onFrame(bmp)
                }
            } catch (e: Exception) {
                if (screen === s) onEnd("Экран: " + (e.message ?: "соединение закрыто"))
            } finally {
                try {
                    s.close()
                } catch (e: Exception) {
                }
            }
        }
        t.isDaemon = true
        screenThread = t
        t.start()
    }

    fun stopScreen() {
        val s = screen
        screen = null
        screenThread?.interrupt()
        screenThread = null
        try {
            s?.close()
        } catch (e: Exception) {
        }
    }

    /** Ищет ПК широковещательным запросом. Возвращает IP или null. */
    fun discover(): String? {
        return try {
            DatagramSocket().use { s ->
                s.broadcast = true
                s.soTimeout = 1500
                val msg = "MOUSEMOBILE?".toByteArray()
                s.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
                val p = DatagramPacket(ByteArray(128), 128)
                s.receive(p)
                if (String(p.data, 0, p.length).startsWith("MOUSEMOBILE")) p.address.hostAddress else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
