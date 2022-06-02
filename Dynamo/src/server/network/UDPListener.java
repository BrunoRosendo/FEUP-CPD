package server.network;

import common.Message;
import server.cluster.MembershipService;
import server.storage.StorageService;
import server.storage.TransferService;

import java.io.*;
import java.net.*;
import java.util.concurrent.ExecutorService;

public class UDPListener implements Runnable {
    private final StorageService storageService;
    private final MembershipService membershipService;
    private final TransferService transferService;
    private final ExecutorService executorService;
    private final MulticastSocket multicastSocket;

    public UDPListener(StorageService storageService, MembershipService membershipService, TransferService transferService,
                       ExecutorService executorService, MulticastSocket multicastSocket) {
        this.storageService = storageService;
        this.membershipService = membershipService;
        this.transferService = transferService;
        this.executorService = executorService;
        this.multicastSocket = multicastSocket;
    }

    public void run() {
        try {
            InetSocketAddress group = new InetSocketAddress(
                    this.membershipService.getMulticastIpAddr(),
                    this.membershipService.getMulticastIPPort());
            NetworkInterface netInf = NetworkInterface.getByIndex(0);
            this.multicastSocket.joinGroup(group, netInf);

            System.out.println("Listening UDP messages");
            while (true) {
                byte[] msg = new byte[Message.MAX_MSG_SIZE];
                DatagramPacket packet = new DatagramPacket(msg, msg.length);

                this.multicastSocket.receive(packet);
                try {
                    final Message message = new Message(packet.getData());
                    executorService.submit(() -> processEvent(message));

                    if (message.getAction().equals("exit"))
                        break;
                } catch (IOException e) {
                    System.err.println(e.getMessage());
                }
            }

            this.multicastSocket.leaveGroup(group, netInf);
            this.multicastSocket.close();
        } catch (SocketException se) {
            System.out.println("[UDPListener] Detected SocketException.");
        } catch (IOException e) {
            System.out.println("Error opening UDP server");
            throw new RuntimeException(e);
        }
    }

    private void processEvent(Message message) {
        InputStream is = new ByteArrayInputStream(message.getBody());
        BufferedReader br = new BufferedReader(new InputStreamReader(is));

        try {
            String nodeId = br.readLine();

            // If processing message from himself
            if (this.membershipService.getNodeId().equals(nodeId)) return;

            switch (message.getAction()) {
                case "electionPing" -> {
                    System.out.println("Received election ping from " + nodeId);
                }
                case "join" -> {
                    this.handleJoinLeave(nodeId, br, true);
                }
                case "leave" -> {
                    this.handleJoinLeave(nodeId, br, false);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleJoinLeave(String nodeId, BufferedReader br, boolean isJoin) throws IOException {
        int tcpPort, membershipCounter;
        tcpPort = Integer.parseInt(br.readLine());
        membershipCounter = Integer.parseInt(br.readLine());
        System.out.printf("Received message from: %s (port %d). Membership Counter: %d%n", nodeId, tcpPort,
                membershipCounter);

        if (isJoin) this.membershipService.handleJoinRequest(nodeId, tcpPort, membershipCounter);
        else this.membershipService.handleLeaveRequest(nodeId, membershipCounter);
    }
}
