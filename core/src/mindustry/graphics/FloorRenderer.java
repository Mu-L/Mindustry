package mindustry.graphics;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.struct.IntSet.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;

import static mindustry.Vars.*;

/**
 * general implementation:
 *
 * caching:
 * 1. create fixed-size float array for rendering into
 * 2. for each chunk, cache each layer into buffer; record layer boundary indices (alternatively, create mesh per layer for fast recache)
 * 3. create mesh for this chunk based on buffer size, copy buffer into mesh
 *
 * rendering:
 * 1. iterate through visible chunks
 * 2. activate the shader vertex attributes beforehand
 * 3. bind each mesh individually, draw it
 *
 * */
public class FloorRenderer{
    private static final VertexAttribute[] attributes = {VertexAttribute.position, VertexAttribute.color, VertexAttribute.texCoords};
    private static final int
        chunksize = 30, //todo 32?
        chunkunits = chunksize * tilesize,
        vertexSize = 2 + 1 + 2,
        spriteSize = vertexSize * 4,
        maxSprites = chunksize * chunksize * 9;
    private static final float pad = tilesize/2f;
    //if true, chunks are rendered on-demand; this causes small lag spikes and is generally not needed for most maps
    private static final boolean dynamic = false;

    private float[] vertices = new float[maxSprites * vertexSize * 4];
    private int vidx;
    private FloorRenderBatch batch = new FloorRenderBatch();
    private Shader shader;
    private Texture texture;
    private TextureRegion error;

    private IndexData indexData;
    private ChunkMesh[][][] cache;
    private IntSet drawnLayerSet = new IntSet();
    private IntSet recacheSet = new IntSet();
    private IntSeq drawnLayers = new IntSeq();
    private ObjectSet<CacheLayer> used = new ObjectSet<>();

    private Seq<Runnable> underwaterDraw = new Seq<>(Runnable.class);
    //alpha value of pixels cannot exceed the alpha of the surface they're being drawn on
    private Blending underwaterBlend = new Blending(
    Gl.srcAlpha, Gl.oneMinusSrcAlpha,
    Gl.dstAlpha, Gl.oneMinusSrcAlpha
    );

    public FloorRenderer(){
        short j = 0;
        short[] indices = new short[maxSprites * 6];
        for(int i = 0; i < indices.length; i += 6, j += 4){
            indices[i] = j;
            indices[i + 1] = (short)(j + 1);
            indices[i + 2] = (short)(j + 2);
            indices[i + 3] = (short)(j + 2);
            indices[i + 4] = (short)(j + 3);
            indices[i + 5] = j;
        }

        indexData = new IndexBufferObject(true, indices.length){
            @Override
            public void dispose(){
                //there is never a need to dispose this index buffer
            }
        };
        indexData.set(indices, 0, indices.length);

        shader = new Shader(
        """
        attribute vec4 a_position;
        attribute vec4 a_color;
        attribute vec2 a_texCoord0;
        uniform mat4 u_projectionViewMatrix;
        varying vec4 v_color;
        varying vec2 v_texCoords;

        void main(){
           v_color = a_color;
           v_color.a = v_color.a * (255.0/254.0);
           v_texCoords = a_texCoord0;
           gl_Position =  u_projectionViewMatrix * a_position;
        }
        """,
        """
        varying vec4 v_color;
        varying vec2 v_texCoords;
        uniform sampler2D u_texture;

        void main(){
          gl_FragColor = v_color * texture2D(u_texture, v_texCoords);
        }
        """);

        Events.on(WorldLoadEvent.class, event -> clearTiles());
    }

    /** Queues up a cache change for a tile. Only runs in render loop. */
    public void recacheTile(Tile tile){
        //recaching all layers may not be necessary
        recacheSet.add(Point2.pack(tile.x / chunksize, tile.y / chunksize));
    }

    public void drawFloor(){
        if(cache == null){
            return;
        }

        Camera camera = Core.camera;

        float pad = tilesize/2f;

        int
            minx = (int)((camera.position.x - camera.width/2f - pad) / chunkunits),
            miny = (int)((camera.position.y - camera.height/2f - pad) / chunkunits),
            maxx = Mathf.ceil((camera.position.x + camera.width/2f + pad) / chunkunits),
            maxy = Mathf.ceil((camera.position.y + camera.height/2f + pad) / chunkunits);

        int layers = CacheLayer.all.length;

        drawnLayers.clear();
        drawnLayerSet.clear();

        Rect bounds = camera.bounds(Tmp.r3);

        //preliminary layer check
        for(int x = minx; x <= maxx; x++){
            for(int y = miny; y <= maxy; y++){

                if(!Structs.inBounds(x, y, cache)) continue;

                if(cache[x][y].length == 0){
                    cacheChunk(x, y);
                }

                ChunkMesh[] chunk = cache[x][y];

                //loop through all layers, and add layer index if it exists
                for(int i = 0; i < layers; i++){
                    if(chunk[i] != null && i != CacheLayer.walls.id && chunk[i].bounds.overlaps(bounds)){
                        drawnLayerSet.add(i);
                    }
                }
            }
        }

        IntSetIterator it = drawnLayerSet.iterator();
        while(it.hasNext){
            drawnLayers.add(it.next());
        }

        drawnLayers.sort();

        beginDraw();

        for(int i = 0; i < drawnLayers.size; i++){
            drawLayer(CacheLayer.all[drawnLayers.get(i)]);
        }

        underwaterDraw.clear();
    }

    public void beginc(){
        shader.bind();
        shader.setUniformMatrix4("u_projectionViewMatrix", Core.camera.mat);

        //only ever use the base environment texture
        texture.bind(0);
    }

    public void checkChanges(){
        if(recacheSet.size > 0){
            //recache one chunk at a time
            IntSetIterator iterator = recacheSet.iterator();
            while(iterator.hasNext){
                int chunk = iterator.next();
                cacheChunk(Point2.x(chunk), Point2.y(chunk));
            }

            recacheSet.clear();
        }
    }

    public void drawUnderwater(Runnable run){
        underwaterDraw.add(run);
    }

    public void beginDraw(){
        if(cache == null){
            return;
        }

        Draw.flush();

        beginc();

        Gl.enable(Gl.blend);
    }

    public void drawLayer(CacheLayer layer){
        if(cache == null){
            return;
        }

        Camera camera = Core.camera;

        int
            minx = (int)((camera.position.x - camera.width/2f - pad) / chunkunits),
            miny = (int)((camera.position.y - camera.height/2f - pad) / chunkunits),
            maxx = Mathf.ceil((camera.position.x + camera.width/2f + pad) / chunkunits),
            maxy = Mathf.ceil((camera.position.y + camera.height/2f + pad) / chunkunits);

        layer.begin();

        Rect bounds = camera.bounds(Tmp.r3);

        for(int x = minx; x <= maxx; x++){
            for(int y = miny; y <= maxy; y++){

                if(!Structs.inBounds(x, y, cache) || cache[x][y].length == 0){
                    continue;
                }

                var mesh = cache[x][y][layer.id];

                if(mesh != null && mesh.bounds.overlaps(bounds)){
                    mesh.render(shader, Gl.triangles, 0, mesh.getMaxVertices() * 6 / 4);
                }
            }
        }

        //every underwater object needs to be drawn once per cache layer, which sucks.
        if(layer.liquid && underwaterDraw.size > 0){

            Draw.blend(underwaterBlend);

            var items = underwaterDraw.items;
            int len = underwaterDraw.size;
            for(int i = 0; i < len; i++){
                items[i].run();
            }

            Draw.flush();
            Draw.blend(Blending.normal);
            Blending.normal.apply();
            beginDraw();
        }

        layer.end();
    }

    private void cacheChunk(int cx, int cy){
        used.clear();

        for(int tilex = Math.max(cx * chunksize - 1, 0); tilex < (cx + 1) * chunksize + 1 && tilex < world.width(); tilex++){
            for(int tiley = Math.max(cy * chunksize - 1, 0); tiley < (cy + 1) * chunksize + 1 && tiley < world.height(); tiley++){
                Tile tile = world.rawTile(tilex, tiley);
                boolean wall = tile.block().cacheLayer != CacheLayer.normal;

                if(wall){
                    used.add(tile.block().cacheLayer);
                }

                if(!wall || world.isAccessible(tilex, tiley)){
                    used.add(tile.floor().cacheLayer);
                }
            }
        }

        if(cache[cx][cy].length == 0){
            cache[cx][cy] = new ChunkMesh[CacheLayer.all.length];
        }

        var meshes = cache[cx][cy];

        for(CacheLayer layer : CacheLayer.all){
            if(meshes[layer.id] != null){
                meshes[layer.id].dispose();
            }
            meshes[layer.id] = null;
        }

        for(CacheLayer layer : used){
            meshes[layer.id] = cacheChunkLayer(cx, cy, layer);
        }
    }

    private ChunkMesh cacheChunkLayer(int cx, int cy, CacheLayer layer){
        vidx = 0;

        Batch current = Core.batch;
        Core.batch = batch;

        for(int tilex = cx * chunksize; tilex < (cx + 1) * chunksize; tilex++){
            for(int tiley = cy * chunksize; tiley < (cy + 1) * chunksize; tiley++){
                Tile tile = world.tile(tilex, tiley);
                Floor floor;

                if(tile == null){
                    continue;
                }else{
                    floor = tile.floor();
                }

                if(tile.block().cacheLayer == layer && layer == CacheLayer.walls && !(tile.isDarkened() && tile.data >= 5)){
                    tile.block().drawBase(tile);
                }else if(floor.cacheLayer == layer && (world.isAccessible(tile.x, tile.y) || tile.block().cacheLayer != CacheLayer.walls || !tile.block().fillsTile)){
                    floor.drawBase(tile);
                }else if(floor.cacheLayer != layer && layer != CacheLayer.walls){
                    floor.drawNonLayer(tile, layer);
                }
            }
        }

        Core.batch = current;

        int floats = vidx;
        ChunkMesh mesh = new ChunkMesh(true, floats / vertexSize, 0, attributes,
            cx * tilesize * chunksize - tilesize/2f, cy * tilesize * chunksize - tilesize/2f,
            (cx+1) * tilesize * chunksize + tilesize/2f, (cy+1) * tilesize * chunksize + tilesize/2f);

        mesh.setVertices(vertices, 0, vidx);
        //all vertices are shared
        mesh.indices = indexData;

        return mesh;
    }

    public void clearTiles(){
        //dispose all old meshes
        if(cache != null){
            for(var x : cache){
                for(var y : x){
                    for(var mesh : y){
                        if(mesh != null){
                            mesh.dispose();
                        }
                    }
                }
            }
        }

        recacheSet.clear();
        int chunksx = Mathf.ceil((float)(world.width()) / chunksize), chunksy = Mathf.ceil((float)(world.height()) / chunksize);
        cache = new ChunkMesh[chunksx][chunksy][dynamic ? 0 : CacheLayer.all.length];

        texture = Core.atlas.find("grass1").texture;
        error = Core.atlas.find("env-error");

        //pre-cache chunks
        if(!dynamic){
            Time.mark();

            for(int x = 0; x < chunksx; x++){
                for(int y = 0; y < chunksy; y++){
                    cacheChunk(x, y);
                }
            }

            Log.debug("Generated world mesh: @ms", Time.elapsed());
        }
    }

    static class ChunkMesh extends Mesh{
        Rect bounds = new Rect();

        ChunkMesh(boolean isStatic, int maxVertices, int maxIndices, VertexAttribute[] attributes, float minX, float minY, float maxX, float maxY){
            super(isStatic, maxVertices, maxIndices, attributes);

            bounds.set(minX, minY, maxX - minX, maxY - minY);
        }
    }

    class FloorRenderBatch extends Batch{
        //TODO: alternate clipping approach, can be more accurate
        /*
        float minX, minY, maxX, maxY;

        void reset(){
            minX = Float.POSITIVE_INFINITY;
            minY = Float.POSITIVE_INFINITY;
            maxX = 0f;
            maxY = 0f;
        }
        */

        @Override
        protected void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height, float rotation){

            //substitute invalid regions with error
            if(region.texture != texture && region != error){
                draw(error, x, y, originX, originY, width, height, rotation);
                return;
            }

            float[] verts = vertices;
            int idx = vidx;
            vidx += spriteSize;

            if(!Mathf.zero(rotation)){
                //bottom left and top right corner points relative to origin
                float worldOriginX = x + originX;
                float worldOriginY = y + originY;
                float fx = -originX;
                float fy = -originY;
                float fx2 = width - originX;
                float fy2 = height - originY;

                // rotate
                float cos = Mathf.cosDeg(rotation);
                float sin = Mathf.sinDeg(rotation);

                float x1 = cos * fx - sin * fy + worldOriginX;
                float y1 = sin * fx + cos * fy + worldOriginY;
                float x2 = cos * fx - sin * fy2 + worldOriginX;
                float y2 = sin * fx + cos * fy2 + worldOriginY;
                float x3 = cos * fx2 - sin * fy2 + worldOriginX;
                float y3 = sin * fx2 + cos * fy2 + worldOriginY;
                float x4 = x1 + (x3 - x2);
                float y4 = y3 - (y2 - y1);

                float u = region.u;
                float v = region.v2;
                float u2 = region.u2;
                float v2 = region.v;

                float color = this.colorPacked;

                verts[idx] = x1;
                verts[idx + 1] = y1;
                verts[idx + 2] = color;
                verts[idx + 3] = u;
                verts[idx + 4] = v;

                verts[idx + 5] = x2;
                verts[idx + 6] = y2;
                verts[idx + 7] = color;
                verts[idx + 8] = u;
                verts[idx + 9] = v2;

                verts[idx + 10] = x3;
                verts[idx + 11] = y3;
                verts[idx + 12] = color;
                verts[idx + 13] = u2;
                verts[idx + 14] = v2;

                verts[idx + 15] = x4;
                verts[idx + 16] = y4;
                verts[idx + 17] = color;
                verts[idx + 18] = u2;
                verts[idx + 19] = v;
            }else{
                float fx2 = x + width;
                float fy2 = y + height;
                float u = region.u;
                float v = region.v2;
                float u2 = region.u2;
                float v2 = region.v;

                float color = this.colorPacked;

                verts[idx] = x;
                verts[idx + 1] = y;
                verts[idx + 2] = color;
                verts[idx + 3] = u;
                verts[idx + 4] = v;

                verts[idx + 5] = x;
                verts[idx + 6] = fy2;
                verts[idx + 7] = color;
                verts[idx + 8] = u;
                verts[idx + 9] = v2;

                verts[idx + 10] = fx2;
                verts[idx + 11] = fy2;
                verts[idx + 12] = color;
                verts[idx + 13] = u2;
                verts[idx + 14] = v2;

                verts[idx + 15] = fx2;
                verts[idx + 16] = y;
                verts[idx + 17] = color;
                verts[idx + 18] = u2;
                verts[idx + 19] = v;
            }

        }

        @Override
        public void flush(){

        }

        @Override
        public void setShader(Shader shader, boolean apply){
            throw new IllegalArgumentException("cache shader unsupported");
        }

        @Override
        protected void draw(Texture texture, float[] spriteVertices, int offset, int count){
            throw new IllegalArgumentException("cache vertices unsupported");
        }
    }
}
