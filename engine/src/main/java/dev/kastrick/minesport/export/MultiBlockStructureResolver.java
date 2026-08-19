package dev.kastrick.minesport.export;

import dev.kastrick.minesport.region.BlockData;

import java.util.*;

/** Detects real multi-block structures without treating stairs/slabs as compounds. */
public final class MultiBlockStructureResolver {
    private static final Map<String,String> COMPLEMENT=Map.ofEntries(
        Map.entry("lower","upper"),Map.entry("upper","lower"),
        Map.entry("bottom","top"),Map.entry("top","bottom"),
        Map.entry("foot","head"),Map.entry("head","foot"),
        Map.entry("first","second"),Map.entry("second","first")
    );

    private static final Map<String,int[]> DIR=Map.of(
        "north",new int[]{0,0,-1},"south",new int[]{0,0,1},
        "east",new int[]{1,0,0},"west",new int[]{-1,0,0},
        "up",new int[]{0,1,0},"down",new int[]{0,-1,0}
    );
    private static final Map<String,String> OPP=Map.of(
        "north","south","south","north","east","west","west","east","up","down","down","up"
    );

    private MultiBlockStructureResolver() {}

    public static Map<BlockData,String> resolve(List<BlockData> blocks){
        Map<Long,BlockData> index=new HashMap<>(Math.max(16,blocks.size()*2));
        for(BlockData b:blocks)if(!b.isAir())index.put(SpatialKey.of(b.x,b.y,b.z),b);
        Map<BlockData,Set<BlockData>> links=new IdentityHashMap<>();

        for(BlockData b:blocks){
            if(b.isAir())continue;
            detectPartPair(index,links,b,"half");
            detectPartPair(index,links,b,"part");
            detectChestPair(index,links,b);
        }

        // Directional custom structures are still supported, but only when the
        // property reciprocates on the neighbor. This avoids merging ordinary
        // adjacent blocks with unrelated north/south state values.
        for(BlockData b:blocks){
            if(b.isAir())continue;
            for(var e:DIR.entrySet()){
                if(!connectionEnabled(b,e.getKey()))continue;
                int[] d=e.getValue();BlockData n=index.get(SpatialKey.of(b.x+d[0],b.y+d[1],b.z+d[2]));
                if(n==null||n.isAir())continue;
                String opposite=OPP.get(e.getKey());
                if(opposite!=null&&connectionEnabled(n,opposite))link(links,b,n);
            }
        }

        Map<BlockData,String> result=new IdentityHashMap<>();
        Set<BlockData> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        for(BlockData start:blocks){
            if(start.isAir()||visited.contains(start)||!links.containsKey(start))continue;
            ArrayDeque<BlockData> q=new ArrayDeque<>();q.add(start);visited.add(start);
            Set<BlockData> component=Collections.newSetFromMap(new IdentityHashMap<>());
            int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE;
            while(!q.isEmpty()){
                BlockData b=q.removeFirst();component.add(b);
                minX=Math.min(minX,b.x);minY=Math.min(minY,b.y);minZ=Math.min(minZ,b.z);
                for(BlockData n:links.getOrDefault(b,Set.of()))if(visited.add(n))q.addLast(n);
            }
            if(component.size()>1){
                String id="compound_"+minX+"_"+minY+"_"+minZ;
                for(BlockData b:component)result.put(b,id);
            }
        }
        return result;
    }

    private static void detectPartPair(Map<Long,BlockData> index,Map<BlockData,Set<BlockData>> links,BlockData b,String key){
        String value=b.prop(key);String otherValue=COMPLEMENT.get(value);if(otherValue==null)return;
        // Do not treat generic top/bottom values on stairs, slabs, trapdoors,
        // rails, etc. as two-block structures. Only families that are known to
        // use complementary physical halves are eligible.
        if(!isKnownPartFamily(b, value, otherValue))return;
        for(int[] d:DIR.values()){
            BlockData n=index.get(SpatialKey.of(b.x+d[0],b.y+d[1],b.z+d[2]));
            if(n==null||!sameFamily(b,n)||!otherValue.equals(n.prop(key)))continue;
            link(links,b,n);
        }
    }

    private static boolean isKnownPartFamily(BlockData b,String value,String other){
        String name=shortName(b.blockId);
        if(name.contains("door")&&value!=null)return true;
        if(name.contains("bed")&&((value.equals("head")||value.equals("foot"))||(other.equals("head")||other.equals("foot"))))return true;
        String lower=name.toLowerCase(Locale.ROOT);
        return lower.contains("double")&&(!value.equals("top")&&!value.equals("bottom"));
    }

    private static void detectChestPair(Map<Long,BlockData> index,Map<BlockData,Set<BlockData>> links,BlockData b){
        if(!isChest(b))return;
        String type=b.prop("type");
        if(!type.equals("left")&&!type.equals("right"))return;
        String facing=b.prop("facing");
        int[] d=leftRightNeighbor(facing,type);if(d==null)return;
        BlockData n=index.get(SpatialKey.of(b.x+d[0],b.y+d[1],b.z+d[2]));
        if(n==null||!isChest(n))return;
        if(!sameChestFamily(b,n))return;
        if((type.equals("left")&&n.prop("type").equals("right"))||(type.equals("right")&&n.prop("type").equals("left")))link(links,b,n);
    }

    private static int[] leftRightNeighbor(String facing,String type){
        // A vanilla chest's left/right halves are relative to its facing.
        // This table gives the neighbor direction for the opposite half.
        return switch(facing){
            case "north" -> type.equals("left")?new int[]{1,0,0}:new int[]{-1,0,0};
            case "south" -> type.equals("left")?new int[]{-1,0,0}:new int[]{1,0,0};
            case "east"  -> type.equals("left")?new int[]{0,0,-1}:new int[]{0,0,1};
            case "west"  -> type.equals("left")?new int[]{0,0,1}:new int[]{0,0,-1};
            default -> null;
        };
    }

    private static boolean isChest(BlockData b){String n=shortName(b.blockId);return n.equals("chest")||n.equals("trapped_chest")||n.endsWith("_chest");}
    private static boolean sameChestFamily(BlockData a,BlockData b){
        String an=shortName(a.blockId),bn=shortName(b.blockId);
        return an.equals(bn)&&a.prop("facing").equals(b.prop("facing"));
    }
    private static boolean sameFamily(BlockData a,BlockData b){return a.blockId.equals(b.blockId);}
    private static boolean connectionEnabled(BlockData b,String dir){String v=b.prop(dir);return v.equalsIgnoreCase("true")||v.equalsIgnoreCase("connect");}
    private static void link(Map<BlockData,Set<BlockData>> links,BlockData a,BlockData b){links.computeIfAbsent(a,k->Collections.newSetFromMap(new IdentityHashMap<>())).add(b);links.computeIfAbsent(b,k->Collections.newSetFromMap(new IdentityHashMap<>())).add(a);}
    private static String shortName(String id){int i=id.indexOf(':');return i>=0?id.substring(i+1):id;}
}
