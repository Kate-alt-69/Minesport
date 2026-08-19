package dev.kastrick.minesport.export;

import dev.kastrick.minesport.region.BlockData;
import java.io.*;
import java.util.*;

/** OBJ + MTL exporter using the same explicit geometry/UV data as glTF. */
public class ObjExporter {
    public record ExportStats(int blockCount,int quadCount,int vertexCount){
        public static ExportStats of(int blocks,int quads){return new ExportStats(blocks,quads,quads*4);}
    }
    public enum ExportMode { ALL_MERGED,GROUPED_BY_TYPE,INDIVIDUAL }
    @FunctionalInterface public interface ProgressCallback{void onProgress(int done,int total);}
    private record BlockObjectEntry(String name,List<Quad> quads){}

    public static ExportStats exportWithGeometry(List<BlockData> blocks,GeometryBuilder builder,File outputFile,ExportMode mode,boolean optimize,ProgressCallback progress)throws IOException{
        if(outputFile.getParentFile()!=null)outputFile.getParentFile().mkdirs();
        float[] center=BlockGrouper.boundingBoxCenter(blocks);
        Map<BlockData,String> groupedIds=BlockGrouper.computeGroups(blocks);
        Map<BlockData,String> compoundIds=MultiBlockStructureResolver.resolve(blocks);
        Map<String,List<BlockObjectEntry>> groups=new LinkedHashMap<>();
        LinkedHashSet<String> textures=new LinkedHashSet<>();
        int done=0,total=Math.max(blocks.size(),1),solid=0,quads=0;

        for(BlockData b:blocks){
            if(b.isAir()){if(progress!=null)progress.onProgress(++done,total);continue;}
            List<Quad> q=builder.buildBlock(b);if(q.isEmpty()){if(progress!=null)progress.onProgress(++done,total);continue;}
            solid++;quads+=q.size();q.forEach(x->textures.add(x.texturePath()));
            String shortName=BlockGrouper.shortName(b.blockId);
            String physicalName=shortName+BlockGrouper.stateSuffix(b.properties)+"_"+b.x+"_"+b.y+"_"+b.z;
            String key=switch(mode){
                case ALL_MERGED->"__merged__";
                case GROUPED_BY_TYPE->groupedIds.getOrDefault(b,shortName);
                case INDIVIDUAL->compoundIds.getOrDefault(b,physicalName);
            };
            String objectName=mode==ExportMode.INDIVIDUAL?key:(mode==ExportMode.ALL_MERGED?"Minesport_Export":key);
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(new BlockObjectEntry(objectName,q));
            if(progress!=null)progress.onProgress(++done,total);
        }

        File mtl=new File(outputFile.getParent(),outputFile.getName().replace(".obj",".mtl"));
        try(PrintWriter w=new PrintWriter(new BufferedWriter(new FileWriter(outputFile)))){
            w.println("# Minesport OBJ Export");
            w.println("# Optimize requested: "+optimize);
            w.println("mtllib "+mtl.getName());w.println();
            int vo=1,vto=1,vno=1;
            for(var ge:groups.entrySet()){
                String groupName=ge.getKey().replace(':','_').replace('/','_');
                w.println("g "+groupName);w.println("s off");
                for(BlockObjectEntry entry:ge.getValue()){
                    String objectName=entry.name().replace(':','_').replace('/','_');
                    w.println("o "+objectName);
                    Map<String,List<Quad>> byTex=new LinkedHashMap<>();
                    for(Quad q:entry.quads())byTex.computeIfAbsent(q.texturePath(),k->new ArrayList<>()).add(q);
                    for(var te:byTex.entrySet()){
                        w.println("usemtl "+te.getKey().replace(':','_').replace('/','_'));
                        for(Quad q:te.getValue()){
                            float[][] verts=q.verts();float[][] uvs=q.vertexUVs();float[] n=q.normal();
                            for(float[] v:verts)w.printf(Locale.ROOT,"v %.6f %.6f %.6f%n",v[0]-center[0],v[1]-center[1],v[2]-center[2]);
                            for(float[] uv:uvs)w.printf(Locale.ROOT,"vt %.6f %.6f%n",uv[0],1f-uv[1]);
                            w.printf(Locale.ROOT,"vn %.6f %.6f %.6f%n",n[0],n[1],n[2]);
                            w.printf("f %d/%d/%d %d/%d/%d %d/%d/%d %d/%d/%d%n",vo,vto,vno,vo+1,vto+1,vno,vo+2,vto+2,vno,vo+3,vto+3,vno);
                            vo+=4;vto+=4;vno++;
                        }
                    }
                }
                w.println();
            }
        }
        MtlExporter.export(textures,mtl,builder.getResolvers());
        return ExportStats.of(solid,quads);
    }
}
