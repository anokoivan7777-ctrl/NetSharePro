package com.netshare.app;

import android.app.Activity;
import android.graphics.Color;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

public class MainActivity extends Activity {

    private boolean isRunning = false;
    private ServerSocket serverSocket;
    private WifiP2pManager p2pManager;
    private WifiP2pManager.Channel p2pChannel;
    
    private TextView tvStatus;
    private EditText etSsid, etPassword;
    private Button btnToggle;
    public static final int PORT = 1080;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        p2pManager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        if (p2pManager != null) {
            p2pChannel = p2pManager.initialize(this, getMainLooper(), null);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.NEARBY_WIFI_DEVICES"
            }, 1);
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 60, 50, 40);

        TextView title = new TextView(this);
        title.setText("NetShare Pro (Безлимит)");
        title.setTextSize(22);
        title.setTextColor(Color.BLACK);
        layout.addView(title);

        tvStatus = new TextView(this);
        tvStatus.setText("Статус: Остановлен");
        tvStatus.setTextSize(16);
        tvStatus.setPadding(0, 20, 0, 30);
        layout.addView(tvStatus);

        TextView lblSsid = new TextView(this);
        lblSsid.setText("Имя Wi-Fi сети (должно начинаться с DIRECT-):");
        layout.addView(lblSsid);

        etSsid = new EditText(this);
        etSsid.setText("DIRECT-NetShare");
        layout.addView(etSsid);

        TextView lblPass = new TextView(this);
        lblPass.setText("Пароль сети (минимум 8 символов):");
        lblPass.setPadding(0, 20, 0, 0);
        layout.addView(lblPass);

        etPassword = new EditText(this);
        etPassword.setText("88888888");
        layout.addView(etPassword);

        btnToggle = new Button(this);
        btnToggle.setText("ВКЛЮЧИТЬ РАЗДАЧУ");
        btnToggle.setTextSize(18);
        btnToggle.setBackgroundColor(Color.parseColor("#007ACC"));
        btnToggle.setTextColor(Color.WHITE);
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 140);
        params.setMargins(0, 50, 0, 0);
        btnToggle.setLayoutParams(params);
        layout.addView(btnToggle);

        setContentView(layout);

        btnToggle.setOnClickListener(v -> {
            if (!isRunning) startAll();
            else stopAll();
        });
    }

    private void startAll() {
        isRunning = true;
        btnToggle.setText("ОСТАНОВИТЬ");
        btnToggle.setBackgroundColor(Color.RED);
        tvStatus.setText("Запуск Wi-Fi Direct и SOCKS5...");
        tvStatus.setTextColor(Color.parseColor("#008000"));

        startWifiDirectGroup();
        startSocksServer();
    }

    private void startWifiDirectGroup() {
        if (p2pManager == null || p2pChannel == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                WifiP2pConfig config = new WifiP2pConfig.Builder()
                        .setNetworkName(etSsid.getText().toString().trim())
                        .setPassphrase(etPassword.getText().toString().trim())
                        .build();

                p2pManager.createGroup(p2pChannel, config, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        tvStatus.setText("Сеть создана: " + etSsid.getText() + "\nIP: 192.168.49.1 : " + PORT);
                    }
                    @Override
                    public void onFailure(int reason) {
                        tvStatus.setText("Ошибка Wi-Fi Direct: " + reason + "\nСервер работает в режиме кабеля.");
                    }
                });
            } catch (Exception e) {
                fallbackCreateGroup();
            }
        } else {
            fallbackCreateGroup();
        }
    }

    private void fallbackCreateGroup() {
        p2pManager.createGroup(p2pChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                tvStatus.setText("Wi-Fi Direct запущен!\nIP: 192.168.49.1 : " + PORT);
            }
            @Override
            public void onFailure(int reason) {}
        });
    }

    private void stopAll() {
        isRunning = false;
        btnToggle.setText("ВКЛЮЧИТЬ РАЗДАЧУ");
        btnToggle.setBackgroundColor(Color.parseColor("#007ACC"));
        tvStatus.setText("Статус: Остановлен");
        tvStatus.setTextColor(Color.BLACK);

        if (p2pManager != null && p2pChannel != null) {
            p2pManager.removeGroup(p2pChannel, null);
        }

        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (Exception ignored) {}
    }

    private void startSocksServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                while (isRunning) {
                    Socket client = serverSocket.accept();
                    new Thread(new SocksHandler(client)).start();
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private static class SocksHandler implements Runnable {
        private final Socket client;

        public SocksHandler(Socket client) {
            this.client = client;
        }

        @Override
        public void run() {
            try {
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();

                int ver = in.read();
                if (ver != 5) { client.close(); return; }
                int nmethods = in.read();
                byte[] methods = new byte[nmethods];
                in.read(methods);
                out.write(new byte[]{0x05, 0x00});
                out.flush();

                in.read(); // ver
                int cmd = in.read(); // 0x01: TCP, 0x03: UDP
                in.read(); // rsv
                int atyp = in.read();

                if (cmd == 0x01) {
                    String host = readHost(in, atyp);
                    int port = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);

                    Socket remote = new Socket(host, port);
                    out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                    out.flush();
                    pipe(client, remote);
                } else if (cmd == 0x03) {
                    DatagramSocket udpSocket = new DatagramSocket();
                    int localUdpPort = udpSocket.getLocalPort();
                    byte[] bndPort = new byte[]{(byte) (localUdpPort >> 8), (byte) (localUdpPort & 0xFF)};
                    out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, bndPort[0], bndPort[1]});
                    out.flush();
                    handleUdpRelay(client, udpSocket);
                } else {
                    client.close();
                }
            } catch (Exception ignored) {
                try { client.close(); } catch (Exception ignored2) {}
            }
        }

        private String readHost(InputStream in, int atyp) throws Exception {
            if (atyp == 0x01) {
                byte[] ip = new byte[4];
                in.read(ip);
                return InetAddress.getByAddress(ip).getHostAddress();
            } else if (atyp == 0x03) {
                int len = in.read();
                byte[] host = new byte[len];
                in.read(host);
                return new String(host);
            }
            throw new Exception("Unknown ATYP");
        }

        private void handleUdpRelay(Socket controlSocket, DatagramSocket udpSocket) {
            new Thread(() -> {
                try {
                    byte[] buf = new byte[65535];
                    InetAddress clientIp = controlSocket.getInetAddress();
                    int clientUdpPort = -1;

                    while (!controlSocket.isClosed()) {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        udpSocket.receive(packet);

                        if (packet.getAddress().equals(clientIp)) {
                            clientUdpPort = packet.getPort();
                            if (buf[2] != 0) continue;
                            int atyp = buf[3];
                            int offset = 4;
                            InetAddress targetAddr;
                            if (atyp == 0x01) {
                                targetAddr = InetAddress.getByAddress(Arrays.copyOfRange(buf, offset, offset + 4));
                                offset += 4;
                            } else continue;

                            int targetPort = ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF);
                            offset += 2;

                            int payloadLen = packet.getLength() - offset;
                            DatagramPacket outPkt = new DatagramPacket(buf, offset, payloadLen, targetAddr, targetPort);
                            udpSocket.send(outPkt);
                        } else if (clientUdpPort != -1) {
                            byte[] resp = new byte[packet.getLength() + 10];
                            resp[0] = 0; resp[1] = 0; resp[2] = 0; resp[3] = 1;
                            byte[] rawIp = packet.getAddress().getAddress();
                            System.arraycopy(rawIp, 0, resp, 4, 4);
                            resp[8] = (byte) (packet.getPort() >> 8);
                            resp[9] = (byte) (packet.getPort() & 0xFF);
                            System.arraycopy(packet.getData(), 0, resp, 10, packet.getLength());

                            DatagramPacket backPkt = new DatagramPacket(resp, resp.length, clientIp, clientUdpPort);
                            udpSocket.send(backPkt);
                        }
                    }
                } catch (Exception ignored) {}
                finally { udpSocket.close(); }
            }).start();
        }

        private void pipe(Socket a, Socket b) {
            new Thread(() -> forward(a, b)).start();
            forward(b, a);
        }

        private void forward(Socket src, Socket dst) {
            try {
                byte[] buf = new byte[32768];
                InputStream in = src.getInputStream();
                OutputStream out = dst.getOutputStream();
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                    out.flush();
                }
            } catch (Exception ignored) {}
            try { src.close(); } catch (Exception ignored) {}
            try { dst.close(); } catch (Exception ignored) {}
        }
    }
}
