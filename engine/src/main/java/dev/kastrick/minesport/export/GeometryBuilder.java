package dev.kastrick.minesport.export;

import dev.kastrick.minesport.model.*;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.ResolverChain;

import java.util.*;

/** Phase 2 geometry builder. */
public class GeometryBuilder {
    private final ResolverChain resolvers;
    private Map<Long, BlockData> occlusionIndex;
    private final Map<String, Set<String>> cullableFacesCache = new HashMap<>();

    public GeometryBuilder(ResolverChain resolvers) { this.resolvers = resolvers; }
    public ResolverChain getResolvers() { return resolvers; }

    public void enableFaceCulling(List<BlockData> allBlocks) {
        Map<Long, BlockData> index = new HashMap<>(Math.max(16, allBlocks.size() * 2));
        for (BlockData b : allBlocks) if (!b.isAir()) index.put(SpatialKey.of(b.x, b.y, b.z), b);
        this.occlusionIndex = index;
        this.cullableFacesCache.clear();
    }

    public List<Quad> buildBlock(BlockData block) {
        if (block.isAir()) return List.of();

        BlockState bs = resolvers.resolveBlockState(block.blockId);
        if (bs == null) return buildFallbackCube(block);

        List<BlockState.ModelApplication> applications = bs.resolve(block.properties, block.x, block.y, block.z);
        if (applications.isEmpty()) return buildFallbackCube(block);

        var quads = new ArrayList<Quad>();
        for (BlockState.ModelApplication app : applications) {
            BlockModel model = resolvers.resolveModel(app.modelPath);
            if (model == null || model.isEmpty()) continue;
            for (BlockModel.Element element : model.elements) buildElement(element, app, model.textures, block, quads);
        }
        return quads.isEmpty() ? buildFallbackCube(block) : quads;
    }

    private void buildElement(BlockModel.Element el, BlockState.ModelApplication app, Map<String,String> textures, BlockData block, List<Quad> out) {
        float x0=el.from[0]/16f,y0=el.from[1]/16f,z0=el.from[2]/16f;
        float x1=el.to[0]/16f,y1=el.to[1]/16f,z1=el.to[2]/16f;
        float[][] corners={{x0,y0,z0},{x1,y0,z0},{x1,y1,z0},{x0,y1,z0},{x1,y0,z1},{x0,y0,z1},{x0,y1,z1},{x1,y1,z1}};

        if (el.rotation != null) rotateCorners(corners, el.rotation);
        if (app.x != 0 || app.y != 0) applyBlockstateRotation(corners, app.x, app.y);

        record FaceDef(String dir,int[] ci,float[] defUv) {}
        FaceDef[] faceDefs={
            new FaceDef("south",new int[]{0,1,2,3},new float[]{x0*16,y0*16,x1*16,y1*16}),
            new FaceDef("north",new int[]{4,5,6,7},new float[]{x0*16,y0*16,x1*16,y1*16}),
            new FaceDef("east",new int[]{1,4,7,2},new float[]{z0*16,y0*16,z1*16,y1*16}),
            new FaceDef("west",new int[]{5,0,3,6},new float[]{z0*16,y0*16,z1*16,y1*16}),
            new FaceDef("up",new int[]{3,2,7,6},new float[]{x0*16,z0*16,x1*16,z1*16}),
            new FaceDef("down",new int[]{0,5,4,1},new float[]{x0*16,z0*16,x1*16,z1*16})
        };

        for (FaceDef fd:faceDefs) {
            BlockModel.Face face=el.faces.get(fd.dir());
            if(face==null) continue;
            String texPath=face.resolveTexture(textures);
            if(texPath==null||texPath.startsWith("#")) continue;
            if(occlusionIndex!=null&&face.cullface!=null){
                String worldDir=rotateDirection(face.cullface,app.x,app.y);
                if(isFaceOccluded(block,worldDir)) continue;
            }
            float[] uv=face.uv!=null?face.uv.clone():fd.defUv().clone();
            if(face.rotation!=0) uv=rotateUv(uv,face.rotation);

            float[][] verts=new float[4][3];
            for(int i=0;i<4;i++){
                float[] c=corners[fd.ci()[i]];
                verts[i][0]=block.x+c[0];
                verts[i][1]=block.y+c[1];
                verts[i][2]=block.z+c[2];
            }
            out.add(new Quad(verts,uv,texPath,new float[3],face.cullface,face.tintindex));
        }
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
        }
    }

    private void applyBlockstateRotation(float[][] corners,int xRot,int yRot){
        float cx=.5f,cy=.5f,cz=.5f;double yr=Math.toRadians(yRot),xr=Math.toRadians(xRot);
        for(float[] c:corners){
            float dx=c[0]-cx,dy=c[1]-cy,dz=c[2]-cz;
            if(yRot!=0){float cos=(float)Math.cos(yr),sin=(float)Math.sin(yr);float nx=dx*cos-dz*sin;dz=dx*sin+dz*cos;dx=nx;}
            if(xRot!=0){float cos=(float)Math.cos(xr),sin=(float)Math.sin(xr);float ny=dy*cos-dz*sin;dz=dy*sin+dz*cos;dy=ny;}
            c[0]=cx+dx;c[1]=cy+dy;c[2]=cz+dz;
        }
    }

    private float[] rotateUv(float[] uv,int degrees){
        float cx=(uv[0]+uv[2])/2f,cy=(uv[1]+uv[3])/2f,hw=(uv[2]-uv[0])/2f,hh=(uv[3]-uv[1])/2f;
        return switch(degrees){case 90->new float[]{cx-hh,cy-hw,cx+hh,cy+hw};case 180->new float[]{cx-hw,cy-hh,cx+hw,cy+hh};case 270->new float[]{cx+hh,cy+hw,cx-hh,cy-hw};default->uv;};
    }

    private List<Quad> buildFallbackCube(BlockData block){
        float x0=block.x,y0=block.y,z0=block.z,x1=x0+1,y1=y0+1,z1=z0+1;var quads=new ArrayList<Quad>();float[] uv={0,0,16,16};String tex="MISSING_"+block.blockId.replace(":","_");
        quads.add(new Quad(new float[][]{{x0,y0,z0},{x1,y0,z0},{x1,y1,z0},{x0,y1,z0}},uv,tex,new float[3],null,-1));
        quads.add(new Quad(new float[][]{{x1,y0,z1},{x0,y0,z1},{x0,y1,z1},{x1,y1,z1}},uv,tex,new float[3],null,-1));
        quads.add(new Quad(new float[][]{{x1,y0,z0},{x1,y0,z1},{x1,y1,z1},{x1,y1,z0}},uv,tex,new float[3],null,-1));
        quads.add(new Quad(new float[][]{{x0,y0,z1},{x0,y0,z0},{x0,y1,z0},{x0,y1,z1}},uv,tex,new float[3],null,-1));
        quads.add(new Quad(new float[][]{{x0,y1,z0},{x1,y1,z0},{x1,y1,z1},{x0,y1,z1}},uv,tex,new float[3],null,-1));
        quads.add(new Quad(new float[][]{{x0,y0,z1},{x1,y0,z1},{x1,y0,z0},{x0,y0,z0}},uv,tex,new float[3],null,-1));
        return quads;
    }

    private static final Map<String,int[]> DIR_VECTORS=Map.of("north",new int[]{0,0,-1},"south",new int[]{0,0,1},"east",new int[]{1,0,0},"west",new int[]{-1,0,0},"up",new int[]{0,1,0},"down",new int[]{0,-1,0});
    private static final Map<String,String> OPPOSITE_DIR=Map.of("north","south","south","north","east","west","west","east","up","down","down","up");

    private static String rotateDirection(String dir,int xDeg,int yDeg){
        int[] v=DIR_VECTORS.get(dir);if(v==null)return dir;double dx=v[0],dy=v[1],dz=v[2];
        if(yDeg!=0){double yr=Math.toRadians(yDeg),cos=Math.cos(yr),sin=Math.sin(yr);double nx=dx*cos-dz*sin;double nz=dx*sin+dz*cos;dx=nx;dz=nz;}
        if(xDeg!=0){double xr=Math.toRadians(xDeg),cos=Math.cos(xr),sin=Math.sin(xr);double ny=dy*cos-dz*sin;double nz=dy*sin+dz*cos;dy=ny;dz=nz;}
        double ax=Math.abs(dx),ay=Math.abs(dy),az=Math.abs(dz);if(ax>=ay&&ax>=az)return dx>0?"east":"west";if(ay>=ax&&ay>=az)return dy>0?"up":"down";return dz>0?"south":"north";
    }

    private boolean isFaceOccluded(BlockData block,String worldDirection){
        int[] d=DIR_VECTORS.get(worldDirection);if(d==null)return false;
        BlockData neighbor=occlusionIndex.get(SpatialKey.of(block.x+d[0],block.y+d[1],block.z+d[2]));if(neighbor==null||neighbor.isAir())return false;
        String opposite=OPPOSITE_DIR.get(worldDirection);Set<String> faces=cullableFacesCache.computeIfAbsent(cullCacheKey(neighbor),k->computeCullableFaces(neighbor));
        return faces.contains(opposite);
    }

    private Set<String> computeCullableFaces(BlockData b){
        Set<String> result=new HashSet<>();BlockState bs=resolvers.resolveBlockState(b.blockId);if(bs==null)return result;
        for(BlockState.ModelApplication app:bs.resolve(b.properties,b.x,b.y,b.z)){
            BlockModel model=resolvers.resolveModel(app.modelPath);if(model==null)continue;
            for(BlockModel.Element el:model.elements)for(BlockModel.Face face:el.faces.values())if(face.cullface!=null)result.add(rotateDirection(face.cullface,app.x,app.y));
        }
        return result;
    }

    private static String cullCacheKey(BlockData b){return b.blockId+"["+BlockGrouper.stateKey(b.properties)+"]";}
}
