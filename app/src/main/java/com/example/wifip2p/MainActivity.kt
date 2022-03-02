package com.example.wifip2p

import android.content.Context
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.support.v7.app.AppCompatActivity
import android.widget.ArrayAdapter
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.connectionStatusTextView
import kotlinx.android.synthetic.main.activity_main.discoverButton
import kotlinx.android.synthetic.main.activity_main.leaderButton
import kotlinx.android.synthetic.main.activity_main.messageTextView0
import kotlinx.android.synthetic.main.activity_main.messageTextView1
import kotlinx.android.synthetic.main.activity_main.peerListEmptyTextView
import kotlinx.android.synthetic.main.activity_main.peerListView
import kotlinx.android.synthetic.main.activity_main.sendButton0
import kotlinx.android.synthetic.main.activity_main.sendButton1
import kotlinx.android.synthetic.main.activity_main.wifiButton
import kotlinx.android.synthetic.main.activity_main.writeMessageEditText0
import kotlinx.android.synthetic.main.activity_main.writeMessageEditText1
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

class MainActivity :
        AppCompatActivity(),
        WifiP2pManager.PeerListListener,
        WifiP2pManager.ConnectionInfoListener {

    companion object {
        private const val HOST_PORT_NUMBER = 8888
        private const val MESSAGE_READ = 1
    }

    private lateinit var wifiManager: WifiManager
    private lateinit var wifiP2pManager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var wifiP2pBroadcastReceiver: WifiP2pBroadcastReceiver
    private lateinit var wifiP2pIntentFilter: IntentFilter

    private lateinit var handler: Handler
    private val peerList: MutableList<WifiP2pDevice> = mutableListOf()

    private var hasP2pSupport: Boolean = false
    private var isLeader = false

    private val connections: MutableList<Socket> = mutableListOf()
    private lateinit var connection: Socket

    var isWifiP2pEnabled: Boolean = false
        set(value) {
            val text = if (value) "Wifi is ON" else "Wifi is OFF"
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            field = value
        }

    var isConnectedToDevice: Boolean = false
        set(value) {
            if (value) {
                val successMessage = "Connected to device"
                Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show()
            } else {
                connectionStatusTextView.text = "Disconnected from device"
            }
            field = value
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkIfP2pSupport()
        if (!hasP2pSupport) return

        setupWifiP2p()
        setupButtons()
        setupPeerList()
        setupHandler()
    }

    private fun setupWifiP2p() {
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiP2pManager = applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = wifiP2pManager.initialize(this, mainLooper, null)
        wifiP2pBroadcastReceiver = WifiP2pBroadcastReceiver(wifiP2pManager, channel, this)
        wifiP2pIntentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
    }

    private fun setupButtons() {
        setupWifiButton()
        setupDiscoverButton()
        setupSendButtons()
        setupLeaderButton()
    }

    private fun setupWifiButton() {
        wifiButton.setOnClickListener { changeWifiState() }
        updateWifiButtonText()
    }

    private fun setupDiscoverButton() {
        discoverButton.setOnClickListener { discoverPeers() }
    }

    private fun setupSendButtons() {
        sendButton0.setOnClickListener {
            val messageToBeSent: String = writeMessageEditText0.text.toString()
            if (isLeader) {
                sendMessage(messageToBeSent, 0)
            } else {
                sendMessage(messageToBeSent)
            }
        }
        sendButton1.setOnClickListener {
            if (isLeader) {
                val messageToBeSent: String = writeMessageEditText1.text.toString()
                sendMessage(messageToBeSent, 1)
            } else {
                Toast.makeText(this, "Use edit text 0", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendMessage(message: String, destination: Int? = null) {
        val socket: Socket
        if (destination != null) {
            if (destination == 0) {
                if (connections.isNotEmpty()) {
                    socket = connections[0]
                } else {
                    Toast.makeText(this, "No connection 0", Toast.LENGTH_SHORT).show()
                    return
                }
            } else if (destination == 1) {
                if (connections.size >= 2) {
                    socket = connections[1]
                } else {
                    Toast.makeText(this, "No connection 1", Toast.LENGTH_SHORT).show()
                    return
                }
            } else {
                Toast.makeText(this, "Invalid destination: $destination", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            socket = connection
        }
        SendThread(message, socket).start()
    }

    private fun setupLeaderButton() {
        leaderButton.setOnClickListener {
            isLeader = !isLeader
            leaderButton.text = if (isLeader) "Leader" else "Regular"
        }
    }

    private fun setupPeerList() {
        peerListView.emptyView = peerListEmptyTextView
        peerListView.setOnItemClickListener {
                _, _, position, _ ->
            val device: WifiP2pDevice = peerList[position]
            val config = WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
            }

            wifiP2pManager.connect(channel, config, object: WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    val successMessage = "Connecting to ${device.deviceName}"
                    Toast.makeText(this@MainActivity, successMessage, Toast.LENGTH_SHORT).show()
                }

                override fun onFailure(reason: Int) {
                    val failureMessage = "Connection to ${device.deviceName} failed. Error code: $reason"
                    Toast.makeText(this@MainActivity, failureMessage, Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    private fun setupHandler() {
        handler = Handler(Handler.Callback {
                msg: Message ->
            when (msg.what) {
                MESSAGE_READ -> {
                    val receivedMessage: String = msg.obj as String
                    val destination: Int = msg.arg1
                    when (destination) {
                        -1, 0 -> messageTextView0.text = receivedMessage
                        1 -> messageTextView1.text = receivedMessage
                    }
                }
            }
            true
        })
    }

    private fun checkIfP2pSupport() {
        hasP2pSupport = applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) != null
        if (!hasP2pSupport) {
            val failMessage = "This phone doesn't support Wifi P2P"
            connectionStatusTextView.text = failMessage
        }
    }

    private fun changeWifiState() {
        val isWifiEnabled = wifiManager.isWifiEnabled
        wifiManager.isWifiEnabled = !isWifiEnabled
        updateWifiButtonText(!isWifiEnabled)
    }

    private fun updateWifiButtonText(wifiEnabled: Boolean? = null) {
        val isWifiEnabled: Boolean = wifiEnabled ?: wifiManager.isWifiEnabled
        wifiButton.text = if (isWifiEnabled) "Wifi On" else "Wifi Off"
    }

    private fun discoverPeers() {
        wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                val successMessage = "Discovery started"
                connectionStatusTextView.text = successMessage
            }

            override fun onFailure(reason: Int) {
                val failureMessage = "Peer discovery failed. Error code: $reason"
                Toast.makeText(this@MainActivity, failureMessage, Toast.LENGTH_SHORT).show()
            }
        })
    }

    override fun onPeersAvailable(peers: WifiP2pDeviceList) {
        val newPeerList: List<WifiP2pDevice> = peers.deviceList.toList()
        if (newPeerList != peerList) {
            Toast.makeText(this, "Peer list updated", Toast.LENGTH_SHORT).show()
            peerList.clear()
            peerList.addAll(newPeerList)
            updatePeerList()
        }
    }

    private fun updatePeerList() {
        val deviceNamesArray: Array<String> = peerList.map(WifiP2pDevice::deviceName).toTypedArray()
        val arrayAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, deviceNamesArray)
        peerListView.adapter = arrayAdapter
    }

    override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        if (info.groupFormed) {
            if (info.isGroupOwner) {
                ServerThread().start()
                connectionStatusTextView.text = "Server"
            } else {
                val groupOwnerAddress: InetAddress = info.groupOwnerAddress
                ClientThread(groupOwnerAddress).start()
                connectionStatusTextView.text = "Client"
            }
        } else {
            Toast.makeText(this, "Group not formed", Toast.LENGTH_SHORT).show()
        }
    }

    inner class ServerThread : Thread() {
        override fun run() {
//            java.net.BindException: bind failed: EADDRINUSE (Address already in use)
            ServerSocket(HOST_PORT_NUMBER).use {
                serverSocket ->
                val socket: Socket = serverSocket.accept()
                if (isLeader) {
                    connections.add(socket)
                    ReceiveThread(socket, connections.size - 1).start()
                } else {
                    connection = socket
                    ReceiveThread(socket).start()
                }
            }
        }
    }

    inner class ReceiveThread(private val socket: Socket, private val destination: Int? = null) : Thread() {
        override fun run() {
            val inputStream: InputStream = socket.getInputStream()
            val buffer = ByteArray(1024)
            var bytes: Int
            while (true) {
//                java.net.SocketException: recvfrom failed: ETIMEDOUT (Connection timed out)
//                java.net.SocketException: Software caused connection abort
                bytes = inputStream.read(buffer)
                if (bytes > 0) {
                    val receivedMessage = String(buffer, 0, bytes)
                    val arg1: Int = destination ?: -1
                    handler.obtainMessage(MESSAGE_READ, arg1, -1, receivedMessage).sendToTarget()
                }
            }
        }
    }

    inner class ClientThread(
            private val hostAddress: InetAddress)
            : Thread() {
        override fun run() {
            val hostAddress: String = hostAddress.hostAddress
            val hostSocketAddress = InetSocketAddress(hostAddress, HOST_PORT_NUMBER)
            val socket = Socket()
            val timeout = 500000
            try {
                socket.connect(hostSocketAddress, timeout)
                if (isLeader) {
                    connections.add(socket)
                    ReceiveThread(socket, connections.size - 1).start()
                } else {
                    connection = socket
                    ReceiveThread(socket).start()
                }
            } catch (e : SocketTimeoutException) {
                runOnUiThread {
                    val failureMessage = "Connection timed out (${timeout * 1000}s)"
                    Toast.makeText(this@MainActivity, failureMessage, Toast.LENGTH_LONG).show()
                }
            }
        }

    }

    inner class SendThread(private val message: String, private val socket: Socket) : Thread() {
        override fun run() {
            val outputStream: OutputStream = socket.getOutputStream()
            val bytes: ByteArray = message.toByteArray()
            outputStream.write(bytes)
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasP2pSupport) {
            registerReceiver(wifiP2pBroadcastReceiver, wifiP2pIntentFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        if (hasP2pSupport) {
            unregisterReceiver(wifiP2pBroadcastReceiver)
        }
    }
}
