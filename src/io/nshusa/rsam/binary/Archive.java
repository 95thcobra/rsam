package io.nshusa.rsam.binary;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import io.nshusa.rsam.util.CompressionUtil;
import io.nshusa.rsam.util.HashUtils;
import io.nshusa.rsam.util.ByteBufferUtils;

public final class Archive {

	public static final int TITLE_ARCHIVE = 1;
	public static final int CONFIG_ARCHIVE = 2;
	public static final int INTERFACE_ARCHIVE = 3;
	public static final int MEDIA_ARCHIVE = 4;
	public static final int VERSION_LIST_ARCHIVE = 5;
	public static final int TEXTURE_ARCHIVE = 6;
	public static final int WORDENC_ARCHIVE = 7;
	public static final int SOUND_ARCHIVE = 8;

	public static final class ArchiveEntry {

		private final int hash;
		private final int uncompressedSize;
		private final int compressedSize;
		private final byte[] data;

		public ArchiveEntry(int hash, int uncompressedSize, int compressedSize, byte[] data) {
			this.hash = hash;
			this.uncompressedSize = uncompressedSize;
			this.compressedSize = compressedSize;
			this.data = data;
		}

		public int getHash() {
			return hash;
		}

		public int getUncompressedSize() {
			return uncompressedSize;
		}

		public int getCompresseedSize() {
			return compressedSize;
		}

		public byte[] getData() {
			return data;
		}

	}

	private boolean extracted;	
	
	private final Map<Integer, ArchiveEntry> entries = new LinkedHashMap<>();

	public Archive(ArchiveEntry[] entries) {
		Arrays.asList(entries).forEach(it -> this.entries.put(it.getHash(), it));
	}

	public static Archive decode(ByteBuffer buffer) throws IOException {
		final int uncompressedLength = ByteBufferUtils.readU24Int(buffer);
		final int compressedLength = ByteBufferUtils.readU24Int(buffer);

		boolean extracted = false;

		if (uncompressedLength != compressedLength) {
			final byte[] compressed = new byte[compressedLength];
			final byte[] decompressed = new byte[uncompressedLength];
			buffer.get(compressed);
			CompressionUtil.debzip2(compressed, decompressed);
			buffer = ByteBuffer.wrap(decompressed);
			extracted = true;
		}

		final int entries = buffer.getShort() & 0xFFFF;

		final int[] hashes = new int[entries];
		final int[] uncompressedSizes = new int[entries];
		final int[] compressedSizes = new int[entries];

		final ArchiveEntry[] archiveEntries = new ArchiveEntry[entries];

		final ByteBuffer entryBuf = ByteBuffer.wrap(buffer.array());
		entryBuf.position(buffer.position() + entries * 10);

		for (int i = 0; i < entries; i++) {

			hashes[i] = buffer.getInt();
			uncompressedSizes[i] = ByteBufferUtils.readU24Int(buffer);
			compressedSizes[i] = ByteBufferUtils.readU24Int(buffer);

			final byte[] entryData = new byte[compressedSizes[i]];
			entryBuf.get(entryData);

			archiveEntries[i] = new ArchiveEntry(hashes[i], uncompressedSizes[i], compressedSizes[i], entryData);
		}

		final Archive archive = new Archive(archiveEntries);
		archive.extracted = extracted;

		return archive;
	}
	
	public synchronized byte[] encode() throws IOException {
		int size = 2 + entries.size() * 10;
		
		for (ArchiveEntry file : entries.values()) {
			size += file.getCompresseedSize();
		}
		
		ByteBuffer buffer;
		if (!extracted) {
			buffer = ByteBuffer.allocate(size + 6);
			ByteBufferUtils.write24Int(buffer, size);
			ByteBufferUtils.write24Int(buffer, size);
		} else {
			buffer = ByteBuffer.allocate(size);
		}
		
		buffer.putShort((short)entries.size());
		
		for (ArchiveEntry entry : entries.values()) {
			buffer.putInt(entry.getHash());
			ByteBufferUtils.write24Int(buffer, entry.getUncompressedSize());
			ByteBufferUtils.write24Int(buffer, entry.getCompresseedSize());
		}
		
		for (ArchiveEntry file : entries.values()) {
			buffer.put(file.getData());
		}
		
		byte[] data;
		if (!extracted) {
			data = buffer.array();
		} else {
			byte[] unzipped = buffer.array();
			byte[] zipped = CompressionUtil.bzip2(unzipped);
			if (unzipped.length == zipped.length) {
				throw new RuntimeException("error zipped size matches original");
			}
			buffer = ByteBuffer.allocate(zipped.length + 6);
			ByteBufferUtils.write24Int(buffer, unzipped.length);
			ByteBufferUtils.write24Int(buffer, zipped.length);
			buffer.put(zipped, 0, zipped.length);
			data = buffer.array();
		}
		
		return data;

	}

	public static ByteBuffer encodeChecksum(Archive[] archives) throws IOException {
		// Integer.BYTES represents the crc stores as an integer which has 4 bytes, + Intger.BYTES because a pre calculated value is after the crcs which is in the form of a int as well
		ByteBuffer buffer = ByteBuffer.allocate((archives.length * Integer.BYTES) + Integer.BYTES);

		Checksum checksum = new CRC32();

		int[] crcs = new int[archives.length];

		for (int i = 0; i < crcs.length; i++) {
			Archive archive = archives[i];

			checksum.reset();

			byte[] encoded = archive.encode();

			checksum.update(encoded, 0, encoded.length);

			int crc = (int)checksum.getValue();

			crcs[i] = crc;

			buffer.putInt(crc);
		}

		// predefined value
		int calculated = 1234;

		for (int index = 0; index < archives.length; index++) {
			calculated = (calculated << 1) + crcs[index];
		}

		buffer.putShort((short)calculated);

		return buffer;
	}

	public ByteBuffer readFile(String name) throws IOException {
		return readFile(HashUtils.nameToHash(name));
	}

	public ByteBuffer readFile(int hash) throws IOException {
		for (ArchiveEntry entry : entries.values()) {

			if (entry.getHash() != hash) {
				continue;
			}

			if (!extracted) {
				byte[] decompressed = new byte[entry.getUncompressedSize()];
				CompressionUtil.debzip2(entry.getData(), decompressed);
				return ByteBuffer.wrap(decompressed);
			} else {
				return ByteBuffer.wrap(entry.getData());
			}

		}
		throw new FileNotFoundException(String.format("file=%d could not be found.", hash));
	}

	public boolean replaceFile(int oldHash, String newName, byte[] data) throws IOException {
		return replaceFile(oldHash, HashUtils.nameToHash(newName), data);
	}

	public boolean replaceFile(int oldHash, int newHash, byte[] data) throws IOException {
		if (entries.containsKey(oldHash)) {
			return false;
		}

		ArchiveEntry entry;
		if (!extracted) {
			byte[] compressed = CompressionUtil.bzip2(data);
			entry = new Archive.ArchiveEntry(newHash, data.length, compressed.length, compressed);
		} else {
			entry = new Archive.ArchiveEntry(newHash, data.length, data.length, data);
		}

		entries.replace(oldHash, entry);
		return true;
	}

	public boolean writeFile(String name, byte[] data) throws IOException {
		return writeFile(HashUtils.nameToHash(name), data);
	}

	public boolean writeFile(int hash, byte[] data) throws IOException {
		if (entries.containsKey(hash)) {
			replaceFile(hash, hash, data);
		}

		ArchiveEntry entry;
		if (!extracted) {
			byte[] compressed = CompressionUtil.bzip2(data);
			entry = new Archive.ArchiveEntry(hash, data.length, compressed.length, compressed);
		} else {
			entry = new Archive.ArchiveEntry(hash, data.length, data.length, data);
		}

		entries.put(hash, entry);
		return true;
	}

	public boolean rename(int oldHash, String newName) {
		return rename(oldHash, HashUtils.nameToHash(newName));
	}

	public boolean rename(int oldHash, int newHash) {
		if (!entries.containsKey(oldHash)) {
			return false;
		}

		ArchiveEntry old = entries.get(oldHash);

		if (old == null) {
			return false;
		}

		entries.replace(oldHash, new ArchiveEntry(newHash, old.getUncompressedSize(), old.getCompresseedSize(), old.getData()));
		return true;
	}
	
	public ArchiveEntry getEntry(String name) throws FileNotFoundException {
		return getEntry(HashUtils.nameToHash(name));
	}
	
	public ArchiveEntry getEntry(int hash) throws FileNotFoundException {
		if (entries.containsKey(hash)) {
			return entries.get(hash);
		}

		throw new FileNotFoundException(String.format("Could not find entry: %d.", hash));
	}
	
	public int indexOf(String name) {
		return indexOf(HashUtils.nameToHash(name));
	}
	
	public int indexOf(int hash) {
		int index = 0;
		for (ArchiveEntry entry : entries.values()) {
			if (entry.getHash() == hash) {
				return index;
			}
			index++;
		}

		return -1;
	}
	
	public boolean contains(String name) {
		return contains(HashUtils.nameToHash(name));
	}
	
	public boolean contains(int hash) {
		return entries.containsKey(hash);
	}
	
	public boolean remove(String name) {
		return remove(HashUtils.nameToHash(name));
	}
	
	public boolean remove(int hash) {
		if (entries.containsKey(hash)) {
			entries.remove(hash);
			return true;
		}
		return false;
	}

	public int getEntryCount() {
		return entries.size();
	}

	public ArchiveEntry[] getEntries() {
		return entries.values().toArray(new ArchiveEntry[0]);
	}

	public boolean isExtracted() {
		return extracted;
	}

}