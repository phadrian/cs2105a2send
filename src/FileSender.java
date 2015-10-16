import java.io.IOException;
import java.net.*;
import java.util.*;
import java.nio.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.*;

public class FileSender {
	
	// Format of the packet
	// <CRC>-------<sequence number>----<data length>----<data>
	// <8bytes>----<4bytes>-------------<4bytes>---------<984bytes>

	public static void main(String[] args) throws Exception 
	{
		if (args.length != 4) {
			System.err.println("Usage: FileSender <host> <port> <source path> <dest path>");
			System.exit(-1);
		}

		// Create address to connect to
		InetSocketAddress addr = new InetSocketAddress(args[0], Integer.parseInt(args[1]));
		
		// Create the socket to send packets
		DatagramSocket socket = new DatagramSocket();
		
		// Create the CRC32 object
		CRC32 crc = new CRC32();
		
		/* 
		 * Create the filepath packet and sending it
		 * ===========================================
		 */
		
		// Read the destination string as a byte[]
		byte[] destBytes = args[3].getBytes();
		
		// Create the packet
		byte[] data = new byte[1000];
		ByteBuffer b = ByteBuffer.wrap(data);
		DatagramPacket pathPacket;
		
		// Reserve space for checksum
		b.clear();
		b.putLong(0);
		
		// Set the sequence number to 0 (the first packet sent)
		b.putInt(0);
		
		// Set the number of data bytes
		b.putInt(destBytes.length);
		
		// Set the data
		b.put(destBytes);
		
		// Calculate and set the checksum in the packet header
		crc.reset();
		crc.update(data, Long.BYTES, data.length - Long.BYTES);
		long checksum = crc.getValue();
		b.rewind();
		b.putLong(checksum);
		
		// Initialize the packet, destPath.length + 8 is likely < data.length(1000)
		pathPacket = new DatagramPacket(data, data.length, addr);
		socket.send(pathPacket);
		
		// Debug message for filepath packet
		System.out.println("Sending filepath packet.");
		System.out.println("Sent CRC:" + checksum + " Contents:" + bytesToHex(data));
		
		/*
		 * Check if the filepath packet is sent correctly
		 * ================================================
		 */
		
		boolean isCorrupted = true;
		while (isCorrupted) {
			// After sending check for the ACK/NAK response from FileReceiver
			byte[] response = new byte[1000];
			DatagramPacket responsePacket = new DatagramPacket(response, response.length);
			socket.receive(responsePacket);
			
			String responseMessage = new String(response).trim();
			
			// If ACK received, no resend required
			if (responseMessage.equals("notCorrupted")) {
				System.out.println("Packet sent correctly.");
				isCorrupted = false;
			} else {
				// NAK received, resend the filepath packet
				System.out.println("Packet corrupted, resending packet.");
				socket.send(pathPacket);
			}
		}
		
		/*
		 * Sending the file as packets
		 * =============================
		 */
		
		// Read in the file into a byte array
		Path filepath = Paths.get(args[2]);
		byte[] fileInBytes = Files.readAllBytes(filepath);
		
		// Calculate the number of packets to send from the size of file
		int numOfDataBytes = data.length - Long.BYTES - Integer.BYTES - Integer.BYTES;
		int num = (int)Math.ceil(fileInBytes.length / (double)numOfDataBytes);
		
		// Reuse the data buffer created earlier
		data = new byte[1000];
		b = ByteBuffer.wrap(data);
		DatagramPacket dataPacket;

		for (int i = 0; i < num; i++) {

			// First assume no packet loss/corruption
			// Reserve space for the checksum
			b.clear();
			b.putLong(0);
			
			// Set the sequence number to i+1, given that 0 is reserved for filename
			b.putInt(i + 1);

			// Used to check if end of file byte[] is reached
			int leftOverBytes;
			
			// Reached the end of the file with leftover bytes < 1000
			if ((leftOverBytes = fileInBytes.length - i * numOfDataBytes) < numOfDataBytes) {
				
				// Set the number of data bytes
				b.putInt(leftOverBytes);
				
				// Set the data in the packet
				b.put(fileInBytes, i * numOfDataBytes, leftOverBytes);
			} else {
				
				// Set the number of data bytes
				b.putInt(numOfDataBytes);
				
				// General case, when the end of the file is not reached
				b.put(fileInBytes, i * numOfDataBytes, numOfDataBytes);
			}
	
			// Set the checksum in the packet header
			crc.reset();
			crc.update(data, Long.BYTES, data.length - Long.BYTES);
			checksum = crc.getValue();
			b.rewind();
			b.putLong(checksum);
			
			// Initialize the packet
			dataPacket = new DatagramPacket(data, data.length, addr);
			
			// Send the packet
			socket.send(dataPacket);
			
			// Debug message
			System.out.println("Sent CRC:" + checksum + " Contents:" + bytesToHex(data));
			
			/*
			 * Check if the data packet was sent correctly
			 * =============================================
			 */
			
			isCorrupted = true;
			while (isCorrupted) {
				// After sending check for the ACK/NAK response from FileReceiver
				byte[] response = new byte[1000];
				DatagramPacket responsePacket = new DatagramPacket(response, response.length);
				socket.receive(responsePacket);
				
				String responseMessage = new String(response).trim();
				
				// If ACK received, no resend required
				if (responseMessage.equals("notCorrupted")) {
					System.out.println("Packet sent correctly.");
					isCorrupted = false;
				} else {
					// garbled ACK/NAK, just resend the data packet
					System.out.println("Packet corrupted, resending packet.");
					socket.send(dataPacket);
				}
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
