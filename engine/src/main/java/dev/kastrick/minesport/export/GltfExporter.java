package dev.kastrick.minesport.export;

import com.google.gson.*;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.ResolverChain;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.Base64;

/** glTF 2.0 exporter: one imported scene object, with all block geometry inside it. */
public class GltfExporter {
    private final ResolverChain resolvers;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final ByteArrayOutputStream bin = new ByteArrayOutputStream();
    private final List<JsonObject> accessors=new ArrayList<>(),bufferViews=new ArrayList<>(),images=new ArrayList<>(),textures=new ArrayList<>(),materials=new ArrayList<>(),meshes=new ArrayList<>(),nodes=new ArrayList<>();
    private final Map<String,Integer> textureMap=new LinkedHashMap<>(),materialMap=new LinkedHashMap<>();

    public GltfExporter(ResolverChain resolvers){this.resolvers=resolvers;}

    public ObjExporter.ExportStats export(List<BlockData> blocks,GeometryBuilder builder,File outputFile,ObjExporter.ExportMode mode,boolean optimize,ObjExporter.ProgressCallback progress)throws IOException{
        if(outputFile.getParentFile()!=null)outputFile.getParentFile().mkdirs();
        File binFile=new File(outputFile.getParent(),outputFile.getName().replace(".gltf",".bin"));
        String objectName=safeObjectName(outputFile.getName().replaceFirst("(?i)\\.gltf$",""));
        float[] center=BlockGrouper.boundingBoxCenter(blocks);
        Map<BlockData,String> groupedIds=BlockGrouper.computeGroups(blocks);
        Map<BlockData,String> compoundIds=MultiBlockStructureResolver.resolve(blocks);
        Map<String,List<BlockData>> groups=new LinkedHashMap<>();
        int done=0,total=Math.max(blocks.size(),1),solid=0,quadCount=0,vertexCount=0;

        for(BlockData b:blocks){
            if(b.isAir()){if(progress!=null)progress.onProgress(++done,total);continue;}
            String shortName=BlockGrouper.shortName(b.blockId);
            String physical=shortName+BlockGrouper.stateSuffix(b.properties)+"_"+b.x+"_"+b.y+"_"+b.z;
            String key=switch(mode){case ALL_MERGED->"__merged__";case GROUPED_BY_TYPE->groupedIds.getOrDefault(b,shortName);case INDIVIDUAL->compoundIds.getOrDefault(b,physical);};
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(b);solid++;if(progress!=null)progress.onProgress(++done,total);
        }

        // Flatten every logical group into one mesh/node. Materials remain separate
        // primitives inside that mesh, but Blender and other importers see one object.
        Map<String,List<Quad>> byTexture=new LinkedHashMap<>();
        for(List<BlockData> group:groups.values())for(BlockData b:group)for(Quad q:builder.buildBlock(b)){byTexture.computeIfAbsent(q.texturePath(),k->new ArrayList<>()).add(q);quadCount++;}

        JsonArray primitives=new JsonArray();
        for(var te:byTexture.entrySet()){
            PrimitiveResult pr=buildPrimitive(te.getValue(),te.getKey(),center,optimize);if(pr==null)continue;primitives.add(pr.primitive());vertexCount+=pr.vertexCount();
        }
        if(primitives.size()>0){
            JsonObject mesh=new JsonObject();mesh.addProperty("name",objectName);mesh.add("primitives",primitives);meshes.add(mesh);
            JsonObject node=new JsonObject();node.addProperty("name",objectName);node.addProperty("mesh",0);nodes.add(node);
        }

        byte[] binData=bin.toByteArray();try(FileOutputStream fos=new FileOutputStream(binFile)){fos.write(binData);}
        JsonObject root=new JsonObject();JsonObject asset=new JsonObject();asset.addProperty("version","2.0");asset.addProperty("generator","Minesport v0.1 by Kastrick");root.add("asset",asset);root.addProperty("scene",0);
        JsonObject scene=new JsonObject();scene.addProperty("name",objectName);JsonArray sceneNodes=new JsonArray();if(!nodes.isEmpty())sceneNodes.add(0);scene.add("nodes",sceneNodes);JsonArray scenes=new JsonArray();scenes.add(scene);root.add("scenes",scenes);
        root.add("nodes",toArray(nodes));root.add("meshes",toArray(meshes));root.add("materials",toArray(materials));root.add("textures",toArray(textures));root.add("images",toArray(images));root.add("accessors",toArray(accessors));root.add("bufferViews",toArray(bufferViews));
        JsonArray samplers=new JsonArray();JsonObject sampler=new JsonObject();sampler.addProperty("magFilter",9728);sampler.addProperty("minFilter",9728);sampler.addProperty("wrapS",33648);sampler.addProperty("wrapT",33648);samplers.add(sampler);root.add("samplers",samplers);
        JsonArray buffers=new JsonArray();JsonObject buffer=new JsonObject();buffer.addProperty("uri",binFile.getName());buffer.addProperty("byteLength",binData.length);buffers.add(buffer);root.add("buffers",buffers);
        try(PrintWriter w=new PrintWriter(new FileWriter(outputFile))){w.println(gson.toJson(root));}
        return new ObjExporter.ExportStats(solid,quadCount,vertexCount);
    }

    private record PrimitiveResult(JsonObject primitive,int vertexCount){}
    private PrimitiveResult buildPrimitive(List<Quad> quads,String texture,float[] center,boolean weld)throws IOException{
        List<float[]> pos=new ArrayList<>(),norm=new ArrayList<>(),uv=new ArrayList<>();List<Integer> idx=new ArrayList<>();Map<String,Integer> map=new LinkedHashMap<>();
        for(Quad q:quads){float[][] vs=q.verts(),us=q.vertexUVs();int[] local=new int[4];float[] n=q.normal();
            for(int i=0;i<4;i++){float x=vs[i][0]-center[0],y=vs[i][1]-center[1],z=vs[i][2]-center[2],u=us[i][0],v=1f-us[i][1];String key=String.format(Locale.ROOT,"%.6f|%.6f|%.6f|%.4f|%.4f|%.4f|%.6f|%.6f",x,y,z,n[0],n[1],n[2],u,v);Integer existing=weld?map.get(key):null;if(existing!=null){local[i]=existing;continue;}int id=pos.size();local[i]=id;pos.add(new float[]{x,y,z});norm.add(new float[]{n[0],n[1],n[2]});uv.add(new float[]{u,v});if(weld)map.put(key,id);}
            idx.add(local[0]);idx.add(local[1]);idx.add(local[2]);idx.add(local[0]);idx.add(local[2]);idx.add(local[3]);
        }
        if(pos.isEmpty())return null;JsonObject prim=new JsonObject(),attrs=new JsonObject();int posAcc=writeVec3(pos,true),normAcc=writeVec3(norm,false),uvAcc=writeVec2(uv),idxAcc=writeIndices(idx);attrs.addProperty("POSITION",posAcc);attrs.addProperty("NORMAL",normAcc);attrs.addProperty("TEXCOORD_0",uvAcc);prim.add("attributes",attrs);prim.addProperty("indices",idxAcc);prim.addProperty("material",getMaterial(texture));prim.addProperty("mode",4);return new PrimitiveResult(prim,pos.size());
    }

    private static String safeObjectName(String s){if(s==null||s.isBlank())return "Minesport_Export";return s.replace(':','_').replace('/','_').replace('\\','_').trim();}
    private int getMaterial(String texture)throws IOException{Integer existing=materialMap.get(texture);if(existing!=null)return existing;int texIndex=getTexture(texture);JsonObject pbr=new JsonObject(),base=new JsonObject();base.addProperty("index",texIndex);pbr.add("baseColorTexture",base);pbr.addProperty("metallicFactor",0);pbr.addProperty("roughnessFactor",1);JsonObject mat=new JsonObject();mat.addProperty("name",texture);mat.add("pbrMetallicRoughness",pbr);BufferedImage img=resolvers.resolveTexture(texture);if(img!=null&&hasAlpha(img))mat.addProperty("alphaMode","BLEND");materials.add(mat);int idx=materials.size()-1;materialMap.put(texture,idx);return idx;}
    private int getTexture(String texture)throws IOException{Integer existing=textureMap.get(texture);if(existing!=null)return existing;BufferedImage img=resolvers.resolveTexture(texture);if(img==null){img=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<16;y++)for(int x=0;x<16;x++)img.setRGB(x,y,0xffff00ff);}ByteArrayOutputStream png=new ByteArrayOutputStream();ImageIO.write(img,"PNG",png);JsonObject image=new JsonObject();image.addProperty("name",texture);image.addProperty("uri","data:image/png;base64,"+Base64.getEncoder().encodeToString(png.toByteArray()));images.add(image);JsonObject tex=new JsonObject();tex.addProperty("sampler",0);tex.addProperty("source",images.size()-1);textures.add(tex);int idx=textures.size()-1;textureMap.put(texture,idx);return idx;}
    private static boolean hasAlpha(BufferedImage img){for(int y=0;y<img.getHeight();y++)for(int x=0;x<img.getWidth();x++)if((img.getRGB(x,y)>>>24)!=255)return true;return false;}
    private int writeVec3(List<float[]> values,boolean position){pad4();int offset=bin.size();ByteBuffer b=ByteBuffer.allocate(values.size()*12).order(ByteOrder.LITTLE_ENDIAN);float[] min={Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE},max={-Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE};for(float[] v:values){for(int i=0;i<3;i++){b.putFloat(v[i]);min[i]=Math.min(min[i],v[i]);max[i]=Math.max(max[i],v[i]);}}writeBytes(b.array());int view=addView(offset,b.array().length,34962);JsonObject acc=new JsonObject();acc.addProperty("bufferView",view);acc.addProperty("componentType",5126);acc.addProperty("count",values.size());acc.addProperty("type","VEC3");if(position){JsonArray mn=new JsonArray(),mx=new JsonArray();for(float v:min)mn.add(v);for(float v:max)mx.add(v);acc.add("min",mn);acc.add("max",mx);}accessors.add(acc);return accessors.size()-1;}
    private int writeVec2(List<float[]> values){pad4();int offset=bin.size();ByteBuffer b=ByteBuffer.allocate(values.size()*8).order(ByteOrder.LITTLE_ENDIAN);for(float[] v:values){b.putFloat(v[0]);b.putFloat(v[1]);}writeBytes(b.array());int view=addView(offset,b.array().length,34962);JsonObject acc=new JsonObject();acc.addProperty("bufferView",view);acc.addProperty("componentType",5126);acc.addProperty("count",values.size());acc.addProperty("type","VEC2");accessors.add(acc);return accessors.size()-1;}
    private int writeIndices(List<Integer> values){pad4();int offset=bin.size();ByteBuffer b=ByteBuffer.allocate(values.size()*4).order(ByteOrder.LITTLE_ENDIAN);for(int v:values)b.putInt(v);writeBytes(b.array());int view=addView(offset,b.array().length,34963);JsonObject acc=new JsonObject();acc.addProperty("bufferView",view);acc.addProperty("componentType",5125);acc.addProperty("count",values.size());acc.addProperty("type","SCALAR");accessors.add(acc);return accessors.size()-1;}
    private int addView(int offset,int length,int target){JsonObject v=new JsonObject();v.addProperty("buffer",0);v.addProperty("byteOffset",offset);v.addProperty("byteLength",length);v.addProperty("target",target);bufferViews.add(v);return bufferViews.size()-1;}
    private void writeBytes(byte[] bytes){bin.writeBytes(bytes);}private void pad4(){while((bin.size()&3)!=0)bin.write(0);}private static JsonArray toArray(List<JsonObject> list){JsonArray a=new JsonArray();for(JsonObject o:list)a.add(o);return a;}
}
