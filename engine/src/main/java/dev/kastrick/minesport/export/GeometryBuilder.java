package dev.kastrick.minesport.export;

import dev.kastrick.minesport.model.*;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.ResolverChain;

import java.util.*;

/** Builds export geometry while preserving Minecraft model UVs through rotations. */
public class GeometryBuilder {
    private final ResolverChain resolvers;
    private Map<Long,BlockData> occlusionIndex = Map.of();
    private final Map<String,Boolean> fullFaceCache = new HashMap<>();
    private boolean faceCullingEnabled;

    private record FaceDef(String dir, int[] corners) {}
    private record Rect(float a0, float a1, float b0, float b1) {}

    private static final FaceDef[] FACES = {
        new FaceDef("south", new int[]{0,1,2,3}),
        new FaceDef("north", new int[]{4,5,6,7}),
        new FaceDef("east",  new int[]{1,4,7,2}),
        new FaceDef("west",  new int[]{5,0,3,6}),
        new FaceDef("up",    new int[]{3,2,7,6}),
        new FaceDef("down",  new int[]{0,5,4,1})
    };

    private static final Map<String,int[]> DIR_VECTORS=Map.of(
        "north",new int[]{0,0,-1},"south",new int[]{0,0,1},
        "east",new int[]{1,0,0},"west",new int[]{-1,0,0},
        "up",new int[]{0,1,0},"down",new int[]{0,-1,0}
    );
    private static final Map<String,String> OPPOSITE=Map.of(
        "north","south","south","north","east","west","west","east","up","down","down","up"
    );

    public GeometryBuilder(ResolverChain resolvers){this.resolvers=resolvers;}
    public ResolverChain getResolvers(){return resolvers;}

    public void enableFaceCulling(List<BlockData> allBlocks){
        Map<Long,BlockData> index=new HashMap<>(Math.max(16,allBlocks.size()*2));
        for(BlockData b:allBlocks)if(!b.isAir())index.put(SpatialKey.of(b.x,b.y,b.z),b);
        occlusionIndex=index;
        faceCullingEnabled=true;
        fullFaceCache.clear();
    }

    public List<Quad> buildBlock(BlockData block){
        if(block.isAir())return List.of();

        BlockState bs=resolvers.resolveBlockState(block.blockId);
        if(bs==null)return isChest(block)?buildChestFallback(block):buildFallbackCube(block);
        List<BlockState.ModelApplication> applications=bs.resolve(block.properties,block.x,block.y,block.z);
        if(applications.isEmpty())return isChest(block)?buildChestFallback(block):buildFallbackCube(block);

        var quads=new ArrayList<Quad>();
        for(BlockState.ModelApplication app:applications){
            BlockModel model=resolvers.resolveModel(app.modelPath);
            if(model==null||model.isEmpty())continue;
            for(BlockModel.Element element:model.elements)buildElement(element,app,model.textures,block,quads);
        }
        if(!quads.isEmpty())return quads;
        return isChest(block)?buildChestFallback(block):buildFallbackCube(block);
    }

    private void buildElement(BlockModel.Element el,BlockState.ModelApplication app,Map<String,String> textures,BlockData block,List<Quad> out){
        float x0=el.from[0]/16f,y0=el.from[1]/16f,z0=el.from[2]/16f;
        float x1=el.to[0]/16f,y1=el.to[1]/16f,z1=el.to[2]/16f;
        float[][] corners={{x0,y0,z0},{x1,y0,z0},{x1,y1,z0},{x0,y1,z0},{x1,y0,z1},{x0,y0,z1},{x0,y1,z1},{x1,y1,z1}};
        if(el.rotation!=null)rotateCorners(corners,el.rotation);
        if(app.x!=0||app.y!=0)applyBlockstateRotation(corners,app.x,app.y);

        for(FaceDef fd:FACES){
            BlockModel.Face face=el.faces.get(fd.dir());
            if(face==null)continue;
            String texPath=face.resolveTexture(textures);
            if(texPath==null||texPath.startsWith("#"))continue;

            String worldDir=rotateDirection(fd.dir(),app.x,app.y);
            if(faceCullingEnabled && occlusionIndex!=null && face.cullface!=null){
                String cullDir=rotateDirection(face.cullface,app.x,app.y);
                if(isFaceOccluded(block,cullDir))continue;
            }

            float[] rect=face.uv!=null?face.uv.clone():defaultUv(fd.dir(),x0,y0,z0,x1,y1,z1);
            float[] explicitUvs=faceUvPoints(fd.dir(),rect);
            if(face.rotation!=0)rotateUvPoints(explicitUvs,face.rotation);
            if(app.uvlock)applyUvLock(explicitUvs,fd.dir(),worldDir,app.x,app.y);

            float[][] verts=new float[4][3];
            for(int i=0;i<4;i++){
                float[] c=corners[fd.corners()[i]];
                verts[i][0]=block.x+c[0];
                verts[i][1]=block.y+c[1];
                verts[i][2]=block.z+c[2];
            }
            out.add(new Quad(verts,explicitUvs,texPath,new float[3],face.cullface,face.tintindex));
        }
    }

    private static float[] defaultUv(String dir,float x0,float y0,float z0,float x1,float y1,float z1){
        return switch(dir){
            case "east","west" -> new float[]{z0*16f,y0*16f,z1*16f,y1*16f};
            case "up","down" -> new float[]{x0*16f,z0*16f,x1*16f,z1*16f};
            default -> new float[]{x0*16f,y0*16f,x1*16f,y1*16f};
        };
    }

    /** Build explicit normalized UVs in raw model-vertex order. */
    private static float[] faceUvPoints(String dir,float[] rect){
        float u1=rect[0]/16f,v1=rect[1]/16f,u2=rect[2]/16f,v2=rect[3]/16f;
        return switch(dir){
            case "north","south" -> new float[]{u1,v2,u2,v2,u2,v1,u1,v1};
            case "east","west" -> new float[]{u2,v2,u1,v2,u1,v1,u2,v1};
            case "up" -> new float[]{u1,v1,u2,v1,u2,v2,u1,v2};
            case "down" -> new float[]{u1,v2,u1,v1,u2,v1,u2,v2};
            default -> new float[]{u1,v2,u2,v2,u2,v1,u1,v1};
        };
    }

    private static void rotateUvPoints(float[] uv,int degrees){
        int turns=((degrees%360)+360)%360/90;
        if(turns==0)return;
        float cx=0.5f,cy=0.5f;
        for(int i=0;i<8;i+=2){
            float x=uv[i]-cx,y=uv[i+1]-cy;
            for(int t=0;t<turns;t++){
                float nx=y,ny=-x;x=nx;y=ny;
            }
            uv[i]=cx+x;uv[i+1]=cy+y;
        }
    }

    private static void applyUvLock(float[] uv,String sourceFace,String worldFace,int xRot,int yRot){
        // uvlock keeps the texture aligned to world directions. For the common
        // blockstate rotations this is the inverse of the model Y rotation.
        int turns=Math.floorMod(yRot,360)/90;
        if(turns!=0)rotateUvPoints(uv,(4-turns)%4*90);
        if(xRot!=0)rotateUvPoints(uv,(4-(Math.floorMod(xRot,360)/90)%4)%4*90);
    }

    private void rotateCorners(float[][] corners,BlockModel.Rotation rot){
        float ox=rot.origin[0]/16f,oy=rot.origin[1]/16f,oz=rot.origin[2]/16f;
        double rad=Math.toRadians(rot.angle);float cos=(float)Math.cos(rad),sin=(float)Math.sin(rad);
        for(float[] c:corners){
            float dx=c[0]-ox,dy=c[1]-oy,dz=c[2]-oz;
            switch(rot.axis){
                case "y"->{c[0]=ox+dx*cos-dz*sin;c[2]=oz+dx*sin+dz*cos;}
                case "x"->{c[1]=oy+dy*cos-dz*sin;c[2]=oz+dy*sin+dz*cos;}
                case "z"->{c[0]=ox+dx*cos-dy*sin;c[1]=oy+dx*sin+dy*cos;}
            }
            if(rot.rescale&&Math.abs(rot.angle)>1e-6){
                float scale=1f/Math.max(.001f,Math.abs(cos));
                switch(rot.axis){
                    case "x"->{c[1]=oy+(c[1]-oy)*scale;c[2]=oz+(c[2]-oz)*scale;}
                    case "y"->{c[0]=ox+(c[0]-ox)*scale;c[2]=oz+(c[2]-oz)*scale;}
                    case "z"->{c[0]=ox+(c[0]-ox)*scale;c[1]=oy+(c[1]-oy)*scale;}
                }
            }
        }
    }

    private static void applyBlockstateRotation(float[][] corners,int xRot,int yRot){
        float cx=.5f,cy=.5f,cz=.5f;
        double yr=Math.toRadians(yRot),xr=Math.toRadians(xRot);
        for(float[] c:corners){
            float dx=c[0]-cx,dy=c[1]-cy,dz=c[2]-cz;
            if(yRot!=0){float cos=(float)Math.cos(yr),sin=(float)Math.sin(yr);float nx=dx*cos-dz*sin;dz=dx*sin+dz*cos;dx=nx;}
            if(xRot!=0){float cos=(float)Math.cos(xr),sin=(float)Math.sin(xr);float ny=dy*cos-dz*sin;dz=dy*sin+dz*cos;dy=ny;}
            c[0]=cx+dx;c[1]=cy+dy;c[2]=cz+dz;
        }
    }

    private static String rotateDirection(String dir,int xDeg,int yDeg){
        int[] v=DIR_VECTORS.get(dir);if(v==null)return dir;
        double dx=v[0],dy=v[1],dz=v[2];
        if(yDeg!=0){double r=Math.toRadians(yDeg),cos=Math.cos(r),sin=Math.sin(r);double nx=dx*cos-dz*sin;dz=dx*sin+dz*cos;dx=nx;}
        if(xDeg!=0){double r=Math.toRadians(xDeg),cos=Math.cos(r),sin=Math.sin(r);double ny=dy*cos-dz*sin;dz=dy*sin+dz*cos;dy=ny;}
        double ax=Math.abs(dx),ay=Math.abs(dy),az=Math.abs(dz);
        if(ax>=ay&&ax>=az)return dx>0?"east":"west";
        if(ay>=ax&&ay>=az)return dy>0?"up":"down";
        return dz>0?"south":"north";
    }

    /** Only cull when the neighbor actually covers the complete shared face. */
    private boolean isFaceOccluded(BlockData block,String worldDirection){
        int[] d=DIR_VECTORS.get(worldDirection);if(d==null||occlusionIndex.isEmpty())return false;
        BlockData n=occlusionIndex.get(SpatialKey.of(block.x+d[0],block.y+d[1],block.z+d[2]));
        if(n==null||n.isAir())return false;
        String key=n.blockId+"["+BlockGrouper.stateKey(n.properties)+"]|"+OPPOSITE.get(worldDirection);
        return fullFaceCache.computeIfAbsent(key,ignored->coversWholeFace(n,OPPOSITE.get(worldDirection)));
    }

    private boolean coversWholeFace(BlockData block,String side){
        BlockState bs=resolvers.resolveBlockState(block.blockId);if(bs==null)return false;
        List<BlockState.ModelApplication> apps=bs.resolve(block.properties,block.x,block.y,block.z);if(apps.isEmpty())return false;
        List<Rect> rects=new ArrayList<>();
        for(BlockState.ModelApplication app:apps){
            BlockModel model=resolvers.resolveModel(app.modelPath);if(model==null)continue;
            for(BlockModel.Element el:model.elements){
                float x0=el.from[0]/16f,y0=el.from[1]/16f,z0=el.from[2]/16f,x1=el.to[0]/16f,y1=el.to[1]/16f,z1=el.to[2]/16f;
                float[][] corners={{x0,y0,z0},{x1,y0,z0},{x1,y1,z0},{x0,y1,z0},{x1,y0,z1},{x0,y0,z1},{x0,y1,z1},{x1,y1,z1}};
                if(el.rotation!=null)rotateCorners(corners,el.rotation);
                if(app.x!=0||app.y!=0)applyBlockstateRotation(corners,app.x,app.y);
                for(FaceDef fd:FACES){
                    BlockModel.Face face=el.faces.get(fd.dir());if(face==null||face.cullface==null)continue;
                    if(!rotateDirection(face.cullface,app.x,app.y).equals(side))continue;
                    Rect rect=projectBoundaryRect(corners,fd.corners(),side);
                    if(rect!=null)rects.add(rect);
                }
            }
        }
        return coversUnitSquare(rects);
    }

    private static Rect projectBoundaryRect(float[][] corners,int[] ids,String side){
        double plane=side.equals("east")||side.equals("up")||side.equals("south")?1.0:0.0;
        float minA=Float.MAX_VALUE,maxA=-Float.MAX_VALUE,minB=Float.MAX_VALUE,maxB=-Float.MAX_VALUE;
        float eps=1e-3f;
        for(int id:ids){
            float[] c=corners[id];
            double boundary=switch(side){case "north","south"->c[2];case "east","west"->c[0];default->c[1];};
            if(Math.abs(boundary-plane)>eps)return null;
            float a=switch(side){case "north","south"->c[0];case "east","west"->c[2];default->c[0];};
            float b=switch(side){case "north","south"->c[1];case "east","west"->c[1];default->c[2];};
            minA=Math.min(minA,a);maxA=Math.max(maxA,a);minB=Math.min(minB,b);maxB=Math.max(maxB,b);
        }
        minA=Math.max(0,minA);maxA=Math.min(1,maxA);minB=Math.max(0,minB);maxB=Math.min(1,maxB);
        if(maxA-minA<eps||maxB-minB<eps)return null;
        return new Rect(minA,maxA,minB,maxB);
    }

    private static boolean coversUnitSquare(List<Rect> rects){
        if(rects.isEmpty())return false;
        TreeSet<Float> xs=new TreeSet<>(),ys=new TreeSet<>();xs.add(0f);xs.add(1f);ys.add(0f);ys.add(1f);
        for(Rect r:rects){xs.add(r.a0);xs.add(r.a1);ys.add(r.b0);ys.add(r.b1);}
        Float[] xa=xs.toArray(Float[]::new),ya=ys.toArray(Float[]::new);
        for(int i=0;i<xa.length-1;i++)for(int j=0;j<ya.length-1;j++){
            float cx=(xa[i]+xa[i+1])/2f,cy=(ya[j]+ya[j+1])/2f;boolean covered=false;
            for(Rect r:rects)if(cx>=r.a0-1e-4&&cx<=r.a1+1e-4&&cy>=r.b0-1e-4&&cy<=r.b1+1e-4){covered=true;break;}
            if(!covered)return false;
        }
        return true;
    }

    private boolean isChest(BlockData block){
        String name=block.blockId.contains(":")?block.blockId.substring(block.blockId.indexOf(':')+1):block.blockId;
        return name.equals("chest")||name.equals("trapped_chest")||name.endsWith("_chest");
    }

    /** Static fallback for chest block entities when no ordinary block model exists. */
    private List<Quad> buildChestFallback(BlockData block){
        String namespace=block.blockId.contains(":")?block.blockId.substring(0,block.blockId.indexOf(':')):"minecraft";
        String name=block.blockId.substring(block.blockId.indexOf(':')+1);
        String texture=namespace+":entity/chest/"+(name.contains("trapped")?"trapped":"normal");
        var out=new ArrayList<Quad>();
        addTexturedBox(out,block,0.0625f,0f,0.0625f,0.9375f,0.625f,0.9375f,texture);
        addTexturedBox(out,block,0.0625f,0.625f,0.0625f,0.9375f,0.9375f,0.9375f,texture);
        return out;
    }

    private void addTexturedBox(List<Quad> out,BlockData block,float x0,float y0,float z0,float x1,float y1,float z1,String tex){
        float[] uv=new float[]{0,1,1,1,1,0,0,0};
        float[][][] faces={
            {{x0,y0,z0},{x1,y0,z0},{x1,y1,z0},{x0,y1,z0}},
            {{x1,y0,z1},{x0,y0,z1},{x0,y1,z1},{x1,y1,z1}},
            {{x1,y0,z0},{x1,y0,z1},{x1,y1,z1},{x1,y1,z0}},
            {{x0,y0,z1},{x0,y0,z0},{x0,y1,z0},{x0,y1,z1}},
            {{x0,y1,z0},{x1,y1,z0},{x1,y1,z1},{x0,y1,z1}},
            {{x0,y0,z1},{x1,y0,z1},{x1,y0,z0},{x0,y0,z0}}
        };
        for(float[][] face:faces){
            float[][] verts=new float[4][3];for(int i=0;i<4;i++){verts[i][0]=block.x+face[i][0];verts[i][1]=block.y+face[i][1];verts[i][2]=block.z+face[i][2];}
            out.add(new Quad(verts,uv,tex,new float[3],null,-1));
        }
    }

    private List<Quad> buildFallbackCube(BlockData block){
        float x0=block.x,y0=block.y,z0=block.z,x1=x0+1,y1=y0+1,z1=z0+1;
        String tex="MISSING_"+block.blockId.replace(":","_");
        var out=new ArrayList<Quad>();
        addTexturedBox(out,block,0,0,0,1,1,1,tex);
        return out;
    }
}
