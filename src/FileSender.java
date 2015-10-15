import java.io.IOException;
import java.net.*;
import java.util.*;
import java.nio.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.*;

public class FileSender {

	public static void main(String[] args) throws Exception 
	{
		if (args.length != 4) {
			System.err.println("Usage: FileSender <host> <port> <source path> <dest path>");
			System.exit(-1);
		}

		// Create address to connect to
		InetSocketAddress addr = new InetSocketAddress(args[0], Integer.parseInt(args[1]));
		
		// Read in the file into a byte array
		Path filepath = Paths.get(args[2]);
		byte[] fileInBytes = Files.readAllBytes(filepath);
		
		// Create the socket to send packets
		DatagramSocket socket = new DatagramSocket();
		CRC32 crc = new CRC32();
		
		// Read the destination string as a byte[]
		byte[] destBytes = args[3].getBytes();
		
		// Create the packet
		byte[] data = new byte[1000];
		ByteBuffer b = ByteBuffer.wrap(data);
		DatagramPacket pathPacket;
		
		// Reserve space for checksum
		b.clear();
		b.putLong(0);
		
		// Set the packet data
		b.put(destBytes);
		
		// Calculate and set the checksum in the packet header
		crc.reset();
		crc.update(data, 8, destBytes.length);
		long checksum = crc.getValue();
		b.rewind();
		b.putLong(checksum);
		
		// Initialize the packet, destPath.length + 8 is likely < data.length(1000)
		pathPacket = new DatagramPacket(data, destBytes.length + 8, addr);
		socket.send(pathPacket);
		
		// Debug message for filepath packet
		System.out.println("Sent CRC:" + checksum + " Contents:" + bytesToHex(data));
		
		// Calculate the number of packets to send from the size of file
		int numOfDataBytes = data.length - 8;
		int num = (int)Math.ceil(fileInBytes.length / (double)numOfDataBytes);
		
		// Reuse the data buffer created earlier
		data = new byte[1000];
		b = ByteBuffer.wrap(data);
		DatagramPacket dataPacket;

		for (int i = 0; i < num; i++) {

			// First assume no packet loss/corruption
			
			b.clear();
			b.putLong(0);

			// Used for last packet instead of data
			byte[] leftOver = null;
			int leftOverBytes;
			
			// Reached the end of the file with leftover bytes < 1000
			if ((leftOverBytes = fileInBytes.length - i * numOfDataBytes) < numOfDataBytes) {
				// Store the leftover bytes in a separate byte[]
				leftOver = new byte[leftOverBytes + 8];
				b = ByteBuffer.wrap(leftOver);
				b.clear();
				b.putLong(0);
				b.put(fileInBytes, i * numOfDataBytes, leftOverBytes);
				
				// Set the checksum in the packet header
				crc.reset();
				crc.update(leftOver, 8, leftOver.length-8);
				checksum = crc.getValue();
				b.rewind();
				b.putLong(checksum);
				
				// Initialize the packet
				dataPacket = new DatagramPacket(leftOver, leftOver.length, addr);
			} else {
				// General case, when the end of the file is not reached
				b.put(fileInBytes, i * numOfDataBytes, numOfDataBytes);
				
				// Set the checksum in the packet header
				crc.reset();
				crc.update(data, 8, data.length-8);
				checksum = crc.getValue();
				b.rewind();
				b.putLong(checksum);
				
				// Initialize the packet
				dataPacket = new DatagramPacket(data, data.length, addr);
			}
	
			// Send the packet
			socket.send(dataPacket);
			
			// Debug message
			if (leftOverBytes < numOfDataBytes) {
				System.out.println("Sent CRC:" + checksum + " Contents:" + bytesToHex(leftOver));
			} else {
				System.out.println("Sent CRC:" + checksum + " Contents:" + bytesToHex(data));
			}
		}
	}
	
	private static void resendIfCorrupt(DatagramSocket sk, DatagramPacket pkt) throws IOException {
		
		boolean isCorrupt = true;
		while (isCorrupt) {
			// After sending check for the ack/nak response to see if transmission 
			// was successful
			byte[] response = new byte[1];
			DatagramPacket responsePacket = new DatagramPacket(response, response.length);
			sk.receive(responsePacket);

			// ack received, continue sending
			if (response[0] == 1) {
				isCorrupt = false;
			} else {
				// nak received, resend and check again
				sk.send(pkt);
			}
		}
	}

	final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
	public static String bytesToHex(byte[] bytes) {
	    char[] hexChars = new char[bytes.length * 2];
	    for ( int j = 0; j < bytes.length; j++ ) {
	        int v = bytes[j] & 0xFF;
	        hexChars[j * 2] = hexArray[v >>> 4];
	        hexChars[j * 2 + 1] = hexArray[v & 0x0F];
	    }
	    return new String(hexChars);
	}
}
