package server.cluster;

import common.Message;
import common.Sender;
import common.Utils;
import server.Constants;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class MembershipService implements ClusterMembership {
    private final TreeMap<String, Node> nodeMap;
    private final String multicastIpAddr;
    private final int multicastIPPort;
    private final String nodeId;

    private final int tcpPort;
    private final String folderPath;
    private int membershipCounter = 0; // NEEDS TO BE STORED IN NON-VOLATILE MEMORY TO SURVIVE NODE CRASHES
    private static final int maxRetransmissions = 3;
    private int retransmissionCounter = 0;
    private final HashSet<String> membershipReplyNodes;
    private final HashSet<String> repliedNodes;

    public MembershipService(String multicastIPAddr, int multicastIPPort, String nodeId, int tcpPort) {
        nodeMap = new TreeMap<>();
        this.multicastIpAddr = multicastIPAddr;
        this.multicastIPPort = multicastIPPort;
        this.nodeId = nodeId;
        this.tcpPort = tcpPort;
        this.folderPath = Utils.generateFolderPath(nodeId);
        this.membershipReplyNodes = new HashSet<>();
        this.repliedNodes = new HashSet<>();
        this.createNodeFolder();

        // CHECK IF CRASHED (IF membershipCounter is EVEN - i.e part of the Cluster)
    }

    /**
     * This methods throws a RuntimeException if it fails
     */
    @Override
    public void join() {
        if (this.membershipCounter == 0 || !isClusterMember(this.membershipCounter)) {
            if (this.membershipCounter != 0) // Edge case for the first join
                this.updateMembershipCounter(this.membershipCounter + 1);
            this.addLog(this.nodeId, this.membershipCounter);
            this.multicastJoin();
            // TODO: OPEN UDP / TCP LISTENER ??
        } else {
            throw new RuntimeException("Attempting to join the cluster while being already a member.");
        }
    }

    /**
     * This methods throws a RuntimeException if it fails
     */
    @Override
    public void leave() {
        if (isClusterMember(this.membershipCounter)) {
            this.removeNodeFromMap(this.nodeId);
            this.updateMembershipCounter(this.membershipCounter + 1);
            this.addLog(this.nodeId, this.membershipCounter);
            this.multicastLeave();
        }
    }

    /**
     * Updates the membershipCounter value and stores it in non-volatile memory
     */
    public void updateMembershipCounter(int newCounter) {
        this.membershipCounter = newCounter;
        String filePath = this.folderPath + Constants.membershipCounterFileName;

        synchronized (filePath.intern()) {
            try {
                File memberCounter = new File(filePath);
                FileWriter counterWriter;
                counterWriter = new FileWriter(memberCounter, false);
                counterWriter.write(String.valueOf(newCounter));
                counterWriter.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public TreeMap<String, Node> getNodeMap() {
        return nodeMap;
    }

    private void multicastJoin() {
        byte[] joinBody = buildMembershipBody();
        Message msg = new Message("REQ", "join", joinBody);

        // Add this node information
        this.addNodeToMap(this.nodeId, this.tcpPort);
        this.addLog(this.nodeId, this.membershipCounter);


        while (this.retransmissionCounter < maxRetransmissions) {
            int elapsedTime = 0;
            try {
                Sender.sendMulticast(msg.toBytes(), this.multicastIpAddr, this.multicastIPPort);
                while (elapsedTime < Constants.timeoutTime) {
                    Thread.sleep(Constants.multicastStepTime);
                    if (this.membershipReplyNodes.size() >= Constants.numMembershipMessages) {
                        this.membershipReplyNodes.clear();
                        this.retransmissionCounter = 0;
                        System.out.println("New Node joined the distributed store");
                        return;
                    }

                    elapsedTime += Constants.multicastStepTime;
                }
            } catch (InterruptedException | IOException e) {
                throw new RuntimeException(e);
            }

            this.retransmissionCounter++;
        }

        System.out.println("Prime Node joined the distributed store.");
    }

    private void multicastLeave() {
        byte[] leaveBody = buildMembershipBody();
        Message msg = new Message("REQ", "leave", leaveBody);

        try {
            Sender.sendMulticast(msg.toBytes(), this.multicastIpAddr, this.multicastIPPort);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        System.out.println("Node left the distributed store.");
    }

    /**
     * Body has nodeId, tcp port and membership Counter
     */
    private byte[] buildMembershipBody() {
        StringBuilder sb = new StringBuilder();
        sb.append(this.nodeId).append(Utils.newLine);
        sb.append(this.tcpPort).append(Utils.newLine);
        sb.append(this.membershipCounter).append(Utils.newLine);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    /**
     * @return true if a node is a member of the nodeMap. False otherwise.
     */
    public static boolean isClusterMember(int counter) {
        return counter % 2 == 0;
    }

    public int getMulticastIPPort() {
        return multicastIPPort;
    }

    public String getMulticastIpAddr() {
        return multicastIpAddr;
    }

    /**
     * Adds a new node to the nodeMap.
     */
    public void addNodeToMap(String newNodeId, int newNodePort) {
        String key = Utils.generateKey(newNodeId);
        if (!this.nodeMap.containsKey(key)) {
            Node newNode = new Node(newNodeId, newNodePort);
            this.nodeMap.put(key, newNode);
        }
    }

    /***
     * Removes a node from the map with a specific id
     * 
     * @param oldNodeId nodeId
     */
    public void removeNodeFromMap(String oldNodeId) {
        String key = Utils.generateKey(oldNodeId);
        this.nodeMap.remove(key);
    }

    private void createNodeFolder() {
        File folder = new File(this.folderPath);

        if (!folder.mkdirs() && !folder.isDirectory()) {
            System.out.println("Error creating the node's folder: " + this.folderPath);
        } else {
            // The membership log should be updated with the one received by other nodes
            // already belonging to the system
            this.initializeNodeFiles();
        }
    }

    private void initializeNodeFiles() {
        try {
            String counterPath = this.folderPath + Constants.membershipCounterFileName;

            synchronized (counterPath.intern()) {
                File memberCounter = new File(counterPath);
                boolean foundCounter = false;
                if (!memberCounter.createNewFile()) {
                    // Recover membership counter
                    Scanner counterScanner = new Scanner(memberCounter);
                    if (counterScanner.hasNextInt()) {
                        this.membershipCounter = counterScanner.nextInt();
                        foundCounter = true;
                    }

                    counterScanner.close();
                }
                if (!foundCounter) {
                    // Create file and store membership counter
                    FileWriter counterWriter = new FileWriter(memberCounter, false);
                    counterWriter.write(String.valueOf(this.membershipCounter));
                    counterWriter.close();
                }
            }

            String logPath = this.folderPath + Constants.membershipLogFileName;

            synchronized (logPath.intern()) {
                File memberLog = new File(logPath);

                // Set initial log to be the current node
                FileWriter writer = new FileWriter(memberLog, false);
                writer.write(String.format("%s %d", this.nodeId, this.membershipCounter));
                writer.close();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Adds a new log to the beginning of the membership log removing existing logs
     * for that nodeId
     */
    public void addLog(String newNodeId, int newMemberCounter) {
        String logPath = this.folderPath + Constants.membershipLogFileName;

        synchronized (logPath.intern()) {
            try {
                File file = new File(logPath);
                boolean isDeprecatedLog = false;

                FileReader fr = new FileReader(file); // reads the file
                BufferedReader br = new BufferedReader(fr);
                String line;

                // Check if there is a more recent log for this node
                while ((line = br.readLine()) != null) {
                    String[] lineData = line.split(" ");
                    String lineId = lineData[0];
                    if (newNodeId.equals(lineId)) {
                        int lineCounter = Integer.parseInt(lineData[1]);
                        if (lineCounter >= newMemberCounter)
                            isDeprecatedLog = true;
                        break;
                    }
                }

                if (!isDeprecatedLog) {
                    List<String> filteredFile = new ArrayList<>();

                    filteredFile.add(String.format("%s %d", newNodeId, newMemberCounter));
                    filteredFile.addAll(
                            Files.lines(file.toPath()).filter(streamLine -> {
                                Optional<String> optId = Arrays.stream(streamLine.split(" ")).findFirst();
                                String rowId = optId.orElse("");
                                return !newNodeId.equals(rowId);
                            }).toList());

                    Files.write(file.toPath(), filteredFile, StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING);

                    // If the newMemberCounter is even, it is already added by the nodeMap received
                    if (!isClusterMember(newMemberCounter)) {
                        // Remove the node from the nodeMap
                        this.removeNodeFromMap(newNodeId);
                    }
                }
            } catch (FileNotFoundException e) {
                System.out.println("An error occurred.");
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public byte[] buildMembershipMsgBody() {
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();

        try {
            // Write node id
            byteOut.write(this.nodeId.getBytes(StandardCharsets.UTF_8));
            byteOut.write(Utils.newLine.getBytes(StandardCharsets.UTF_8));

            String logPath = this.folderPath + Constants.membershipLogFileName;

            synchronized (logPath.intern()) {
                File file = new File(logPath);
                Scanner myReader = new Scanner(file);
                for (int i = 0; i < Constants.numLogEvents; i++) {
                    if (!myReader.hasNextLine())
                        break;
                    String line = myReader.nextLine();
                    byteOut.write(line.getBytes(StandardCharsets.UTF_8));
                    byteOut.write(Utils.newLine.getBytes(StandardCharsets.UTF_8));
                }
                myReader.close();
            }

            byteOut.write(Utils.newLine.getBytes(StandardCharsets.UTF_8));

            for (Node node : this.getNodeMap().values()) {
                String entryLine = node.getId() + " " + node.getPort() + "\n";
                byteOut.write(entryLine.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return byteOut.toByteArray();
    }

    public HashSet<String> getMembershipReplyNodes() {
        return membershipReplyNodes;
    }

    public void handleJoinRequest(String nodeId, int tcpPort, int membershipCounter) {
        if (this.repliedNodes.contains(Utils.generateKey(nodeId))) {
            System.out.println("Received join from node that was already replied.");
            return;
        }

        // Updates view of the cluster membership and adds the log
        this.addNodeToMap(nodeId, tcpPort);
        this.addLog(nodeId, membershipCounter);

        // Updated membership info TODO: CHECK IF THIS IS OK
        this.repliedNodes.clear();
        this.repliedNodes.add(Utils.generateKey(nodeId));

        final int randomWait = new Random().nextInt(Constants.maxResponseTime);
        try {
            Thread.sleep(randomWait);

            final byte[] body = this.buildMembershipMsgBody();
            Message msg = new Message("REQ", "join", body);
            Sender.sendTCPMessage(msg.toBytes(), nodeId, tcpPort);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void handleMembershipResponse(Message message) {
        if (this.getMembershipReplyNodes().size() >= Constants.numMembershipMessages)
            return;

        System.out.println("Received tcp reply to join");
        InputStream is = new ByteArrayInputStream(message.getBody());
        BufferedReader br = new BufferedReader(new InputStreamReader(is));

        String line, nodeId;
        final ArrayList<String> membershipLogs = new ArrayList<>();
        try {
            nodeId = br.readLine();
            this.getMembershipReplyNodes().add(Utils.generateKey(nodeId)); // adds node to set if not present

            while ((line = br.readLine()) != null) {
                if (line.isEmpty())
                    break; // Reached the end of membership log
                membershipLogs.add(line);
            }

            while ((line = br.readLine()) != null) {
                String[] data = line.split(" ");
                String newNodeId = data[0];
                int newNodePort = Integer.parseInt(data[1]);
                this.addNodeToMap(newNodeId, newNodePort); // Check what happens when adding node that already exists
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        for (String newLog : membershipLogs) {
            String[] logData = newLog.split(" ");
            String logId = logData[0];
            int logCounter = Integer.parseInt(logData[1]);
            this.addLog(logId, logCounter);
        }

        System.out.println("Received membership Logs: " + membershipLogs + "\nnodeMap: " + this.getNodeMap());
    }

    public int getMembershipCounter() {
        return membershipCounter;
    }
}
