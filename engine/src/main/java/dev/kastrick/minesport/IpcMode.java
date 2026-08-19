package dev.kastrick.minesport;

import com.google.gson.*;
import dev.kastrick.minesport.export.*;
import dev.kastrick.minesport.region.*;
import dev.kastrick.minesport.resolver.*;
import dev.kastrick.minesport.safety.WorldCopier;

import java.io.*;
import java.util.*;

/** IPC mode for the Go wrapper. */
public class IpcMode {
    private static final Gson GSON = new Gson();
    private static final PrintWriter OUT = new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.out)), true);
    private static final int MAX_CUSTOM_SELECTION = 5_000_000;

    public static void run() {
        send("info", j -> j.addProperty("version", "0.1.0"));
        log("Minesport engine ready (IPC mode)");
        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JsonObject req = GSON.fromJson(line, JsonObject.class);
                    String command = req.has("command") ? req.get("command").getAsString() : "";
                    switch (command) {
                        case "ping" -> send("pong", j -> j.addProperty("message", "pong"));
                        case "export" -> handleExport(req);
                        case "heightmap" -> handleHeightmap(req);
                        case "listBlocks" -> handleListBlocks(req);
                        case "quit" -> { log("Engine shutting down."); return; }
                        default -> error("Unknown command: " + command);
                    }
                } catch (JsonSyntaxException e) {
                    error("Invalid JSON: " + e.getMessage());
                } catch (Exception e) {
                    error("Command failed: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            error("IPC stdin error: " + e.getMessage());
        }
    }

    private static void handleExport(JsonObject req) {
        String worldPath=getString(req,"worldPath","");
        int minX=getInt(req,"minX",-256), minY=getInt(req,"minY",-64), minZ=getInt(req,"minZ",-256);
        int maxX=getInt(req,"maxX",256), maxY=getInt(req,"maxY",320), maxZ=getInt(req,"maxZ",256);
        String format=getString(req,"format","gltf").toLowerCase();
        String exportMode=getString(req,"exportMode","grouped");
        String outputPath=getString(req,"outputPath","");
        if(worldPath.isEmpty()){error("worldPath is required");return;}

        File worldFolder=new File(worldPath);
        if(!worldFolder.exists()||!new File(worldFolder,"level.dat").exists()){error("World not found or invalid: "+worldPath);return;}
        if(outputPath.isEmpty()){
            String home=System.getProperty("user.home");
            String ext=format.equals("gltf")?".gltf":".obj";
            outputPath=home+File.separator+"Minesport_Exports"+File.separator+worldFolder.getName()+"_export"+ext;
        }
        File outFile=new File(outputPath);
        File parent=outFile.getParentFile(); if(parent!=null) parent.mkdirs();

        File tempDir=null;
        try {
            log("Creating temp copy...");
            tempDir=WorldCopier.copyToTemp(worldFolder,msg->log(msg));
            progress(10,"World copy ready");

            log("Scanning region files...");
            File regionDir=new File(tempDir,"region");
            if(!regionDir.exists()){error("No region folder found in world");return;}
            File[] mcaFiles=regionDir.listFiles((d,n)->n.endsWith(".mca"));
            if(mcaFiles==null||mcaFiles.length==0){error("No .mca region files found");return;}

            log("Found "+mcaFiles.length+" region file(s)");
            var allBlocks=new ArrayList<BlockData>();
            for(int fi=0;fi<mcaFiles.length;fi++){
                File mca=mcaFiles[fi];
                log("Reading: "+mca.getName());
                List<BlockData> regionBlocks=RegionReader.readRegion(mca,minX,minY,minZ,maxX,maxY,maxZ,null);
                allBlocks.addAll(regionBlocks);
                int pct=10+(int)((fi+1.0)/mcaFiles.length*30);
                progress(pct,"Read "+mca.getName());
            }
            log("Total blocks: "+allBlocks.size());

            Integer cx=getOptionalInt(req,"centerX"), cy=getOptionalInt(req,"centerY"), cz=getOptionalInt(req,"centerZ");
            Integer rx=getOptionalInt(req,"radiusX"), ry=getOptionalInt(req,"radiusY"), rz=getOptionalInt(req,"radiusZ");
            if(cx!=null&&cy!=null&&cz!=null&&rx!=null&&ry!=null&&rz!=null){
                int before=allBlocks.size();
                allBlocks.removeIf(b->!insideEllipsoid(b,cx,cy,cz,Math.max(rx,1),Math.max(ry,1),Math.max(rz,1)));
                log("Bubble selection: "+allBlocks.size()+" / "+before+" blocks kept (center "+cx+","+cy+","+cz+" · radius "+rx+","+ry+","+rz+")");
            }

            String customSelectionFile=req.has("options")&&req.getAsJsonObject("options").has("customSelectionFile")
                ?req.getAsJsonObject("options").get("customSelectionFile").getAsString():null;
            if(customSelectionFile!=null&&!customSelectionFile.isBlank()){
                Set<Long> exact=loadCustomSelection(new File(customSelectionFile));
                int before=allBlocks.size();
                allBlocks.removeIf(b->!exact.contains(SpatialKey.of(b.x,b.y,b.z)));
                log("Custom selection: "+allBlocks.size()+" / "+before+" blocks kept ("+exact.size()+" coordinate(s) requested)");
            }
            progress(40,"Region scan complete");

            log("Resolving multipart connections...");
            MultipartResolver.resolve(allBlocks);
            progress(45,"Multipart resolved");

            log("Setting up resolvers...");
            var chain=new ResolverChain();
            List<File> resourcePackPaths=getPathList(req,"resourcePacks");
            if(!resourcePackPaths.isEmpty()){
                ResourcePackResolver rp=ResourcePackResolver.load(resourcePackPaths,msg->log(msg));
                if(!rp.isEmpty()){chain.addResolver(rp);log("Resource pack override active ("+resourcePackPaths.size()+" pack(s))");}
            }

            String mcVersion=readMcVersion(tempDir);
            log("MC version: "+mcVersion);
            File mcJar=VanillaResolver.findMinecraftJar(mcVersion);
            if(mcJar!=null&&mcJar.exists()){log("Vanilla resolver: "+mcJar.getName());chain.addResolver(new VanillaResolver(mcJar));}
            else log("[WARN] minecraft.jar not found — vanilla blocks use fallback geometry");

            ModsLocator.LocatedMods located=ModsLocator.locate(worldFolder);
            File modsFolder=located!=null?located.modsFolder():null;
            if(modsFolder==null){for(File c:ModsLocator.candidatePaths(mcVersion)){if(c.exists()){modsFolder=c;break;}}}
            if(modsFolder!=null){
                FabricResolver fab=FabricResolver.load(modsFolder,msg->log(msg));
                if(!fab.getNamespaces().isEmpty()){chain.addResolver(fab);log("Fabric mod namespaces: "+fab.getNamespaces());chain.addResolver(new PolymerResolver(fab));}
                QuiltResolver quilt=QuiltResolver.load(modsFolder,msg->log(msg));
                if(!quilt.getNamespaces().isEmpty()){chain.addResolver(quilt);log("Quilt mod namespaces: "+quilt.getNamespaces());}
                ForgeResolver forge=ForgeResolver.load(modsFolder,msg->log(msg));
                if(!forge.getNamespaces().isEmpty()){chain.addResolver(forge);log("Forge/NeoForge mod namespaces: "+forge.getNamespaces());}
            }

            List<File> dataPackPaths=getPathList(req,"dataPacks");
            if(dataPackPaths.isEmpty()) dataPackPaths=dev.kastrick.minesport.datapack.DataPackBlockTagReader.discoverWorldDataPacks(tempDir);
            if(!dataPackPaths.isEmpty()){
                var tagReader=dev.kastrick.minesport.datapack.DataPackBlockTagReader.load(dataPackPaths,msg->log(msg));
                if(!tagReader.isEmpty())log("Data pack block tags found: "+tagReader.getTagIds());
            }
            progress(50,"Resolvers ready");

            boolean optimize=getBoolOption(req,"optimize",false);
            boolean faceCulling=getBoolOption(req,"faceCulling",false);
            boolean hiddenBlockCulling=getBoolOption(req,"hiddenBlockCulling",false);
            var geoBuilder=new GeometryBuilder(chain);
            if(faceCulling){log("Face culling enabled");geoBuilder.enableFaceCulling(allBlocks);}
            if(hiddenBlockCulling){log("Hidden block culling enabled (experimental)");geoBuilder.enableHiddenBlockCulling(allBlocks);}

            ObjExporter.ExportMode mode=switch(exportMode){case "merged"->ObjExporter.ExportMode.ALL_MERGED;case "individual"->ObjExporter.ExportMode.INDIVIDUAL;default->ObjExporter.ExportMode.GROUPED_BY_TYPE;};
            log("Exporting as "+format.toUpperCase()+"...");
            ObjExporter.ExportStats stats;
            if(format.equals("gltf")){
                stats=new GltfExporter(chain).export(allBlocks,geoBuilder,outFile,mode,optimize,(doneCount,total)->{int pct=50+(int)((doneCount/(double)total)*45);progress(pct,"Building geometry "+doneCount+"/"+total);});
                GltfPostProcessor.fixSamplers(outFile);
                log("glTF sampler normalization complete");
            }else{
                stats=ObjExporter.exportWithGeometry(allBlocks,geoBuilder,outFile,mode,optimize,(doneCount,total)->{int pct=50+(int)((doneCount/(double)total)*45);progress(pct,"Building geometry "+doneCount+"/"+total);});
            }
            progress(100,"Done");
            log("Export stats: "+stats.blockCount()+" blocks, "+stats.quadCount()+" faces, ≤"+stats.vertexCount()+" vertices");
            done(outFile.getAbsolutePath(),stats);
        } catch(Exception e){
            StringWriter sw=new StringWriter();e.printStackTrace(new PrintWriter(sw));error("Export failed: "+e.getMessage()+"\n"+sw);
        } finally { if(tempDir!=null) WorldCopier.cleanupTemp(tempDir); }
    }

    private static String readMcVersion(File tempDir){
        try{var levelDat=new File(tempDir,"level.dat");var root=dev.kastrick.minesport.nbt.NbtReader.readGzip(levelDat);if(root.has("Data")){try{return root.getCompound("Data").getCompound("Version").getString("Name","1.21.10");}catch(Exception ignored){}}}catch(Exception ignored){}
        return "1.21.10";
    }

    private static void send(String type,java.util.function.Consumer<JsonObject> builder){JsonObject obj=new JsonObject();obj.addProperty("type",type);builder.accept(obj);OUT.println(GSON.toJson(obj));}
    private static void log(String msg){send("log",j->j.addProperty("message",msg));}
    private static void progress(int pct,String msg){send("progress",j->{j.addProperty("percent",pct);j.addProperty("message",msg);});}
    private static void done(String outputPath,ObjExporter.ExportStats stats){send("done",j->{j.addProperty("output",outputPath);if(stats!=null){j.addProperty("blockCount",stats.blockCount());j.addProperty("quadCount",stats.quadCount());j.addProperty("vertexCount",stats.vertexCount());}});}
    private static void error(String msg){send("error",j->j.addProperty("message",msg));}
    private static String getString(JsonObject obj,String key,String fallback){return obj.has(key)?obj.get(key).getAsString():fallback;}

    private static Set<Long> loadCustomSelection(File file)throws IOException{
        Set<Long> result=new HashSet<>(); if(!file.exists())return result;
        try(var reader=new com.google.gson.stream.JsonReader(new BufferedReader(new FileReader(file)))){
            reader.beginArray();
            while(reader.hasNext()){
                if(result.size()>=MAX_CUSTOM_SELECTION)throw new IOException("Custom selection exceeds "+MAX_CUSTOM_SELECTION+" blocks");
                reader.beginObject(); int x=0,y=0,z=0; boolean hasX=false,hasY=false,hasZ=false;
                while(reader.hasNext()){
                    switch(reader.nextName()){
                        case "x"-> {x=reader.nextInt();hasX=true;}
                        case "y"-> {y=reader.nextInt();hasY=true;}
                        case "z"-> {z=reader.nextInt();hasZ=true;}
                        default->reader.skipValue();
                    }
                }
                reader.endObject();
                if(!hasX||!hasY||!hasZ)throw new IOException("Custom selection entry is missing x, y, or z");
                result.add(SpatialKey.of(x,y,z));
            }
            reader.endArray();
        }
        return result;
    }

    private static int getInt(JsonObject obj,String key,int fallback){return obj.has(key)?obj.get(key).getAsInt():fallback;}
    private static Integer getOptionalInt(JsonObject obj,String key){return(obj.has(key)&&!obj.get(key).isJsonNull())?obj.get(key).getAsInt():null;}
    private static boolean insideEllipsoid(BlockData b,int cx,int cy,int cz,int rx,int ry,int rz){double dx=(b.x+0.5-cx)/(double)rx,dy=(b.y+0.5-cy)/(double)ry,dz=(b.z+0.5-cz)/(double)rz;return dx*dx+dy*dy+dz*dz<=1.0;}
    private static boolean getBoolOption(JsonObject req,String key,boolean fallback){if(!req.has("options")||!req.get("options").isJsonObject())return fallback;JsonObject options=req.getAsJsonObject("options");if(!options.has(key))return fallback;return Boolean.parseBoolean(options.get(key).getAsString());}

    private static List<File> getPathList(JsonObject req,String key){
        List<File> result=new ArrayList<>();if(!req.has("options")||!req.get("options").isJsonObject())return result;JsonObject options=req.getAsJsonObject("options");if(!options.has(key))return result;String raw=options.get(key).getAsString();if(raw==null||raw.isBlank())return result;
        for(String part:raw.split(";")){String trimmed=part.trim();if(trimmed.isEmpty())continue;File f=new File(trimmed);if(f.exists())result.add(f);else log("[WARN] Path not found, skipping: "+trimmed);}return result;
    }

    private static void handleHeightmap(JsonObject req){
        String worldPath=getString(req,"worldPath","");int scale=getInt(req,"scale",4);if(worldPath.isEmpty()){error("worldPath required");return;}
        File regionDir=new File(worldPath,"region");if(!regionDir.exists()){error("No region folder: "+worldPath);return;}
        try{
            log("Generating heightmap (scale="+scale+")...");String b64=dev.kastrick.minesport.region.HeightmapGenerator.generateBase64Png(regionDir,scale);if(b64==null){error("No region files found");return;}
            File[] mcaFiles=regionDir.listFiles((d,n)->n.endsWith(".mca"));int minRX=Integer.MAX_VALUE,minRZ=Integer.MAX_VALUE,maxRX=Integer.MIN_VALUE,maxRZ=Integer.MIN_VALUE;
            if(mcaFiles!=null)for(File f:mcaFiles){String[] p=f.getName().split("\\.");if(p.length<4)continue;try{int rx=Integer.parseInt(p[1]),rz=Integer.parseInt(p[2]);minRX=Math.min(minRX,rx);minRZ=Math.min(minRZ,rz);maxRX=Math.max(maxRX,rx);maxRZ=Math.max(maxRZ,rz);}catch(NumberFormatException ignored){}}
            if(minRX==Integer.MAX_VALUE){error("No valid region coordinates found");return;}
            final int minX=minRX*512,minZ=minRZ*512,maxX=(maxRX+1)*512,maxZ=(maxRZ+1)*512;final String imgData=b64;
            send("heightmap",j->{j.addProperty("image",imgData);j.addProperty("minX",minX);j.addProperty("minZ",minZ);j.addProperty("maxX",maxX);j.addProperty("maxZ",maxZ);j.addProperty("scale",scale);});
        }catch(Exception e){error("Heightmap failed: "+e.getMessage());}
    }

    private static void handleListBlocks(JsonObject req){
        String worldPath=getString(req,"worldPath","");int minX=getInt(req,"minX",-256),minY=getInt(req,"minY",-64),minZ=getInt(req,"minZ",-256),maxX=getInt(req,"maxX",256),maxY=getInt(req,"maxY",320),maxZ=getInt(req,"maxZ",256);
        if(worldPath.isEmpty()){error("worldPath required");return;}
        File worldFolder=new File(worldPath);if(!worldFolder.exists()||!new File(worldFolder,"level.dat").exists()){error("World not found or invalid: "+worldPath);return;}
        File tempWorldCopy=null;
        try{
            log("Preparing block list for 3D preview...");tempWorldCopy=WorldCopier.copyToTemp(worldFolder,msg->log(msg));File regionDir=new File(tempWorldCopy,"region");if(!regionDir.exists()){error("No region folder found in world");return;}
            File[] mcaFiles=regionDir.listFiles((d,n)->n.endsWith(".mca"));if(mcaFiles==null||mcaFiles.length==0){error("No .mca region files found");return;}
            var allBlocks=new ArrayList<BlockData>();for(File mca:mcaFiles)allBlocks.addAll(RegionReader.readRegion(mca,minX,minY,minZ,maxX,maxY,maxZ,null));
            Integer cx=getOptionalInt(req,"centerX"),cy=getOptionalInt(req,"centerY"),cz=getOptionalInt(req,"centerZ"),rx=getOptionalInt(req,"radiusX"),ry=getOptionalInt(req,"radiusY"),rz=getOptionalInt(req,"radiusZ");
            if(cx!=null&&cy!=null&&cz!=null&&rx!=null&&ry!=null&&rz!=null){int fcx=cx,fcy=cy,fcz=cz,frx=Math.max(rx,1),fry=Math.max(ry,1),frz=Math.max(rz,1);allBlocks.removeIf(b->!insideEllipsoid(b,fcx,fcy,fcz,frx,fry,frz));}
            allBlocks.removeIf(BlockData::isAir);log("Block list: "+allBlocks.size()+" solid block(s)");
            File outFile=File.createTempFile("minesport_blocks_",".json");outFile.deleteOnExit();
            try(var writer=new com.google.gson.stream.JsonWriter(new BufferedWriter(new FileWriter(outFile)))){
                writer.beginArray();for(BlockData b:allBlocks){writer.beginObject();writer.name("x").value(b.x);writer.name("y").value(b.y);writer.name("z").value(b.z);writer.name("id").value(b.blockId);int[] color=dev.kastrick.minesport.region.HeightmapGenerator.colorForBlock(b.blockId);writer.name("r").value(color[0]);writer.name("g").value(color[1]);writer.name("b").value(color[2]);writer.endObject();}writer.endArray();
            }
            send("blocksReady",j->{j.addProperty("file",outFile.getAbsolutePath());j.addProperty("count",allBlocks.size());});
        }catch(Exception e){error("List blocks failed: "+e.getMessage());}finally{if(tempWorldCopy!=null)WorldCopier.cleanupTemp(tempWorldCopy);}
    }
}
