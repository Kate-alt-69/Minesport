package dev.kastrick.minesport.nbt;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Reads Minecraft's Named Binary Tag (NBT) format. */
public class NbtReader {
    public static final byte TAG_END=0,TAG_BYTE=1,TAG_SHORT=2,TAG_INT=3,TAG_LONG=4,TAG_FLOAT=5,TAG_DOUBLE=6,TAG_BYTE_ARRAY=7,TAG_STRING=8,TAG_LIST=9,TAG_COMPOUND=10,TAG_INT_ARRAY=11,TAG_LONG_ARRAY=12;

    private static final int MAX_LIST_ENTRIES=1_000_000;
    private static final int MAX_ARRAY_ENTRIES=16_000_000;
    private static final int MAX_NESTING_DEPTH=512;

    public static NbtCompound readGzip(File file)throws IOException{try(var gzip=new GZIPInputStream(new FileInputStream(file));var data=new DataInputStream(new BufferedInputStream(gzip))){return readRoot(data);}}
    public static NbtCompound readBytes(byte[] bytes)throws IOException{try(var data=new DataInputStream(new ByteArrayInputStream(bytes))){return readRoot(data);}}

    private static NbtCompound readRoot(DataInputStream in)throws IOException{
        byte type=in.readByte();
        if(type!=TAG_COMPOUND)throw new IOException("Expected root TAG_Compound, got: "+type);
        readString(in);
        return readCompound(in,0);
    }

    private static Object readPayload(DataInputStream in,byte type,int depth)throws IOException{
        return switch(type){
            case TAG_BYTE->in.readByte();
            case TAG_SHORT->in.readShort();
            case TAG_INT->in.readInt();
            case TAG_LONG->in.readLong();
            case TAG_FLOAT->in.readFloat();
            case TAG_DOUBLE->in.readDouble();
            case TAG_BYTE_ARRAY->readByteArray(in);
            case TAG_STRING->readString(in);
            case TAG_LIST->readList(in,depth+1);
            case TAG_COMPOUND->readCompound(in,depth+1);
            case TAG_INT_ARRAY->readIntArray(in);
            case TAG_LONG_ARRAY->readLongArray(in);
            default->throw new IOException("Unknown NBT tag type: "+type);
        };
    }

    private static void checkDepth(int depth)throws IOException{if(depth>MAX_NESTING_DEPTH)throw new IOException("NBT nesting depth exceeds "+MAX_NESTING_DEPTH);}

    private static NbtCompound readCompound(DataInputStream in,int depth)throws IOException{
        checkDepth(depth);
        var map=new LinkedHashMap<String,Object>();byte type;
        while((type=in.readByte())!=TAG_END){String name=readString(in);Object value=readPayload(in,type,depth);map.put(name,value);}return new NbtCompound(map);
    }

    private static List<Object> readList(DataInputStream in,int depth)throws IOException{
        checkDepth(depth);
        byte elementType=in.readByte();int size=in.readInt();
        if(size<0||size>MAX_LIST_ENTRIES)throw new IOException("Invalid NBT list length: "+size);
        var list=new ArrayList<Object>(size);
        for(int i=0;i<size;i++)list.add(readPayload(in,elementType,depth));
        return list;
    }

    private static String readString(DataInputStream in)throws IOException{int len=in.readUnsignedShort();byte[] bytes=new byte[len];in.readFully(bytes);return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);}
    private static byte[] readByteArray(DataInputStream in)throws IOException{int len=in.readInt();if(len<0||len>MAX_ARRAY_ENTRIES)throw new IOException("Invalid NBT byte-array length: "+len);byte[] arr=new byte[len];in.readFully(arr);return arr;}
    private static int[] readIntArray(DataInputStream in)throws IOException{int len=in.readInt();if(len<0||len>MAX_ARRAY_ENTRIES)throw new IOException("Invalid NBT int-array length: "+len);int[] arr=new int[len];for(int i=0;i<len;i++)arr[i]=in.readInt();return arr;}
    private static long[] readLongArray(DataInputStream in)throws IOException{int len=in.readInt();if(len<0||len>MAX_ARRAY_ENTRIES)throw new IOException("Invalid NBT long-array length: "+len);long[] arr=new long[len];for(int i=0;i<len;i++)arr[i]=in.readLong();return arr;}
}
