package hipi.image.io;

import hipi.image.FloatImage;
import hipi.image.ImageHeader;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Iterator;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifReader;
import com.sun.image.codec.jpeg.JPEGCodec;
import com.sun.image.codec.jpeg.JPEGEncodeParam;
import com.sun.image.codec.jpeg.JPEGImageDecoder;
import com.sun.image.codec.jpeg.JPEGImageEncoder;

public class PNGImageUtil implements ImageDecoder, ImageEncoder{


	private static final PNGImageUtil static_object = new PNGImageUtil();
	/** black and white image mode. */    
	private static final byte BW_MODE = 0;
	/** grey scale image mode. */    
	private static final byte GREYSCALE_MODE = 1;
	/** full color image mode. */    
	private static final byte COLOR_MODE = 2;
	private CRC32 crc;
	public static PNGImageUtil getInstance() {
		return static_object;
	}

	public ImageHeader decodeImageHeader(InputStream is) throws IOException {
		ImageHeader header = new ImageHeader();
		DataInputStream in = new DataInputStream(is);
		readSignature(in);

		boolean trucking = true;
		while (trucking) {
			try {
				// Read the length.
				int length = in.readInt();
				if (length < 0)
					throw new IOException("Sorry, that file is too long.");
				// Read the type.
				byte[] typeBytes = new byte[4];
				in.readFully(typeBytes);
				String typeString = new String(typeBytes, "UTF8");
				if(typeString.equals("IHDR")) {
					// Read the data.
					byte[] data = new byte[length];
					in.readFully(data);
					// Read the CRC.
					long crc = in.readInt() & 0x00000000ffffffffL; // Make it
					// unsigned.
					if (verifyCRC(typeBytes, data, crc) == false)
						throw new IOException("That file appears to be corrupted.");

					PNGChunk chunk = static_object.new PNGChunk(typeBytes, data);
					header.image_width      = (int) chunk.getUnsignedInt(0);
					header.image_height     = (int) chunk.getUnsignedInt(4);
					header.image_bit_depth  = chunk.getUnsignedByte(8);
					break;
				}
				else {
					// skip the data associated, plus the crc signature
					in.skipBytes(length+4);
				}
			} catch (EOFException eofe) {
				trucking = false;
			}
		}
		return header;
	}

	public FloatImage decodeImage(InputStream is) throws IOException {		
		DataInputStream dataIn = new DataInputStream(is);
		readSignature(dataIn);
		PNGData chunks = readChunks(dataIn);

		long widthLong = chunks.getWidth();
		long heightLong = chunks.getHeight();
		if (widthLong > Integer.MAX_VALUE || heightLong > Integer.MAX_VALUE)
			throw new IOException("That image is too wide or tall.");
		int width = (int) widthLong;
		int height = (int) heightLong;
		int bitsPerPixel = (int) chunks.getBitsPerPixel();
		float[] pels = new float[width * height * 3];
		byte[] image_bytes = chunks.getImageData();

		for(int i = 0; i < image_bytes.length; i++)
			pels[i] = image_bytes[i]&0xff;
		FloatImage image = new FloatImage(width, height, 3, pels); //hard code 3

		return image;
	}

	protected static void readSignature(DataInputStream in) throws IOException {
		long signature = in.readLong();
		if (signature != 0x89504e470d0a1a0aL)
			throw new IOException("PNG signature not found!");
	}

	protected static PNGData readChunks(DataInputStream in) throws IOException {
		PNGData chunks = static_object.new PNGData();

		boolean trucking = true;
		while (trucking) {
			try {
				// Read the length.
				int length = in.readInt();
				if (length < 0)
					throw new IOException("Sorry, that file is too long.");
				// Read the type.
				byte[] typeBytes = new byte[4];
				in.readFully(typeBytes);
				// Read the data.
				byte[] data = new byte[length];
				in.readFully(data);
				// Read the CRC.
				long crc = in.readInt() & 0x00000000ffffffffL; // Make it
				// unsigned.
				if (verifyCRC(typeBytes, data, crc) == false)
					throw new IOException("That file appears to be corrupted.");

				PNGChunk chunk = static_object.new PNGChunk(typeBytes, data);
				chunks.add(chunk);
			} catch (EOFException eofe) {
				trucking = false;
			}
		}
		return chunks;
	}

	protected static boolean verifyCRC(byte[] typeBytes, byte[] data, long crc) {
		CRC32 crc32 = new CRC32();
		crc32.update(typeBytes);
		crc32.update(data);
		long calculated = crc32.getValue();
		return (calculated == crc);
	}
	/*
	public ImageHeader createSimpleHeader(FloatImage image) {
		// TODO Auto-generated method stub
		return null;
	}
	 */
	class PNGData {
		private int mNumberOfChunks;

		private PNGChunk[] mChunks;

		public PNGData() {
			mNumberOfChunks = 0;
			mChunks = new PNGChunk[10];
		}
		public void printAll(){
			System.out.println("number of chunks: " + mNumberOfChunks);
			for(int i = 0; i < mChunks.length; i++)
				System.out.println("(" + mChunks[i].getTypeString() + ", " + ")");
		}
		public void add(PNGChunk chunk) {
			mChunks[mNumberOfChunks++] = chunk;
			if (mNumberOfChunks >= mChunks.length) {
				PNGChunk[] largerArray = new PNGChunk[mChunks.length + 10];
				System.arraycopy(mChunks, 0, largerArray, 0, mChunks.length);
				mChunks = largerArray;
			}
		}

		public long getWidth() {
			return getChunk("IHDR").getUnsignedInt(0);
		}

		public long getHeight() {    return getChunk("IHDR").getUnsignedInt(4);
		}

		public short getBitsPerPixel() {
			return getChunk("IHDR").getUnsignedByte(8);
		}

		public short getColorType() {
			return getChunk("IHDR").getUnsignedByte(9);
		}

		public short getCompression() {
			return getChunk("IHDR").getUnsignedByte(10);
		}

		public short getFilter() {
			return getChunk("IHDR").getUnsignedByte(11);
		}

		public short getInterlace() {
			return getChunk("IHDR").getUnsignedByte(12);
		}


		// keep these methods commented out, in case we need to modify them for later use
		/*
	public ColorModel getColorModel() {
		short colorType = getColorType();
		int bitsPerPixel = getBitsPerPixel();

		if (colorType == 3) {
			byte[] paletteData = getChunk("PLTE").getData();
			int paletteLength = paletteData.length / 3;
			return new IndexColorModel(bitsPerPixel, paletteLength,
					paletteData, 0, false);
		}
		System.out.println("Unsupported color type: " + colorType);
		return null;
	}

	public WritableRaster getRaster() {
		int width = (int) getWidth();
		int height = (int) getHeight();
		int bitsPerPixel = getBitsPerPixel();
		short colorType = getColorType();

		if (colorType == 3) {
			byte[] imageData = getImageData();
			DataBuffer db = new DataBufferByte(imageData, imageData.length);
			WritableRaster raster = Raster.createPackedRaster(db, width,
					height, bitsPerPixel, null);
			return raster;
		} else
			System.out.println("Unsupported color type!");
		return null;
	}
		 */
		public byte[] getImageData() {
			try {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				// Write all the IDAT data into the array.
				for (int i = 0; i < mNumberOfChunks; i++) {
					PNGChunk chunk = mChunks[i];
					if (chunk.getTypeString().equals("IDAT")) {
						out.write(chunk.getData());
					}
				}
				out.flush();
				// Now deflate the data.
				InflaterInputStream in = new InflaterInputStream(
						new ByteArrayInputStream(out.toByteArray()));
				ByteArrayOutputStream inflatedOut = new ByteArrayOutputStream();
				int readLength;
				byte[] block = new byte[8192];
				while ((readLength = in.read(block)) != -1)
					inflatedOut.write(block, 0, readLength);
				inflatedOut.flush();
				byte[] imageData = inflatedOut.toByteArray();
				// Compute the real length.
				int width = (int) getWidth();
				int height = (int) getHeight();
				int bitsPerPixel = getBitsPerPixel();
				int length = width * height * bitsPerPixel / 8 * 3; //hard code the 3 for RGB for now

				byte[] prunedData = new byte[length];

				// We can only deal with non-interlaced images.
				if (getInterlace() == 0) {
					int index = 0;
					for (int i = 0; i < length; i++) {
						if (i % (width * bitsPerPixel / 8 * 3) == 0) { // again, hard code the 3 for RGB
							index++; // Skip the filter byte.
						}
						prunedData[i] = imageData[index++];
					}
				} else
					System.out.println("Couldn't undo interlacing.");

				return prunedData;
			} catch (IOException ioe) {
			}
			return null;
		}

		public PNGChunk getChunk(String type) {
			for (int i = 0; i < mNumberOfChunks; i++)
				if (mChunks[i].getTypeString().equals(type))
					return mChunks[i];
			return null;
		}
	}

	class PNGChunk {
		private byte[] mType;

		private byte[] mData;

		public PNGChunk(byte[] type, byte[] data) {
			mType = type;
			mData = data;
		}

		public String getTypeString() {
			try {
				return new String(mType, "UTF8");
			} catch (UnsupportedEncodingException uee) {
				return "";
			}
		}
		public String getDataString() {
			try {
				return new String(mData, "UTF8");
			} catch (UnsupportedEncodingException uee) {
				return "";
			}		
		}
		public byte[] getData() {
			return mData;
		}

		public long getUnsignedInt(int offset) {
			long value = 0;
			for (int i = 0; i < 4; i++)
				value += (mData[offset + i] & 0xff) << ((3 - i) * 8);
			return value;
		}

		public short getUnsignedByte(int offset) {
			return (short) (mData[offset] & 0x00ff);
		}
	}

	public void encodeImageHeader(ImageHeader header, OutputStream os)
	throws IOException {

	}

	public void encodeImage(FloatImage image, ImageHeader header,
			OutputStream os) throws IOException {
		crc = new CRC32();
		int width = image.getWidth();
		int height = image.getHeight();
		final byte id[] = {-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13};
		write(os, id);
		crc.reset();
		write(os, "IHDR".getBytes());
		write(os, width);
		write(os, height);
		byte head[]=null;

		//TODO: incorporate different modes of encoding
		int mode = COLOR_MODE;
		switch (mode) {
		case BW_MODE: head=new byte[]{1, 0, 0, 0, 0}; break;
		case GREYSCALE_MODE: head=new byte[]{8, 0, 0, 0, 0}; break;
		case COLOR_MODE: head=new byte[]{8, 2, 0, 0, 0}; break;
		}                 
		write(os, head);
		write(os, (int)crc.getValue());
		ByteArrayOutputStream compressed = new ByteArrayOutputStream(65536);
		BufferedOutputStream bos = new BufferedOutputStream( new DeflaterOutputStream(compressed, new Deflater(9)));
		/*
		int pixel;
		int color;
		int colorset;
		 */
		switch (mode) {
		/*
		case BW_MODE: 
			int rest=width%8;
			int bytes=width/8;
			for (int y=0;y<height;y++) {
				bos.write(0);
				for (int x=0;x<bytes;x++) {
					colorset=0;
					for (int sh=0; sh<8; sh++) {
						pixel=image.getRGB(x*8+sh,y);
						color=((pixel >> 16) & 0xff);
						color+=((pixel >> 8) & 0xff);
						color+=(pixel & 0xff);
						colorset<<=1;
						if (color>=3*128)
							colorset|=1;
					}
					bos.write((byte)colorset);
				}
				if (rest>0) {
					colorset=0;
					for (int sh=0; sh<width%8; sh++) {
						pixel=image.getRGB(bytes*8+sh,y);
						color=((pixel >> 16) & 0xff);
						color+=((pixel >> 8) & 0xff);
						color+=(pixel & 0xff);
						colorset<<=1;
						if (color>=3*128)
							colorset|=1;
					}
					colorset<<=8-rest;
					bos.write((byte)colorset);
				}
			}
			break;
		case GREYSCALE_MODE: 
			for (int y=0;y<height;y++) {
				bos.write(0);
				for (int x=0;x<width;x++) {
					pixel=image.getRGB(x,y);
					color=((pixel >> 16) & 0xff);
					color+=((pixel >> 8) & 0xff);
					color+=(pixel & 0xff);
					bos.write((byte)(color/3));
				}
			}
			break;
		 */
		case COLOR_MODE:
			for (int y=0;y<height;y++) {
				bos.write(0);
				for (int x=0;x<width;x++) {
					int r = Math.min(Math.max((int)image.getPixel(x, y, 0), 0), 255);
					int g = Math.min(Math.max((int)image.getPixel(x, y, 1), 0), 255);
					int b = Math.min(Math.max((int)image.getPixel(x, y, 2), 0), 255);
					bos.write((byte)r);
					bos.write((byte)g);
					bos.write((byte)b);
				}
			}
			break;
		}
		bos.close();
		write(os, compressed.size());
		crc.reset();
		write(os, "IDAT".getBytes());
		write(os, compressed.toByteArray());
		write(os, (int) crc.getValue()); 
		write(os, 0);
		crc.reset();
		write(os, "IEND".getBytes());
		write(os, (int) crc.getValue()); 
		os.close();
	}		


	public ImageHeader createSimpleHeader(FloatImage image) {
		return null;
	}

	private byte read(InputStream is) throws IOException {
		byte b = (byte)is.read();
		System.out.print((char)b);
		return(b);
	}

	private int readInt(InputStream is) throws IOException {
		byte b[] = read(is, 4);
		return(((b[0]&0xff)<<24) +
				((b[1]&0xff)<<16) +
				((b[2]&0xff)<<8) +
				((b[3]&0xff)));
	}

	private byte[] read(InputStream is, int count) throws IOException {
		byte[] result = new byte[count];
		for(int i = 0; i < count; i++) {
			result[i] = read(is);
		}
		return(result);
	}

	private boolean compare(byte[] b1, byte[] b2) {
		if(b1.length != b2.length) {
			return(false);
		}
		for(int i = 0; i < b1.length; i++) {
			if(b1[i] != b2[i]) {
				return(false);
			}
		}
		return(true);
	}

	private void checkEquality(byte[] b1, byte[] b2) {
		if(!compare(b1, b2)) {
			//	System.out.println(new String(b1));
			//	System.out.println(new String(b2));
			throw(new RuntimeException("Format error"));
		}
	}


	private void write(OutputStream os, int i) throws IOException {
		byte b[]={(byte)((i>>24)&0xff),(byte)((i>>16)&0xff),(byte)((i>>8)&0xff),(byte)(i&0xff)};
		write(os, b);
	}

	private void write(OutputStream os, byte b[]) throws IOException {
		os.write(b);
		crc.update(b);
	}

}