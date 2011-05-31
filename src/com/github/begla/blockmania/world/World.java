/*
 * Copyright 2011 Benjamin Glatzel <benjamin.glatzel@me.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.begla.blockmania.world;

import com.github.begla.blockmania.Configuration;
import com.github.begla.blockmania.Helper;
import com.github.begla.blockmania.player.Player;
import com.github.begla.blockmania.player.Intersection;
import com.github.begla.blockmania.RenderableObject;
import com.github.begla.blockmania.blocks.Block;
import com.github.begla.blockmania.generators.ChunkGenerator;
import com.github.begla.blockmania.generators.ChunkGeneratorForest;
import com.github.begla.blockmania.generators.ChunkGeneratorFlora;
import com.github.begla.blockmania.generators.ChunkGeneratorLakes;
import com.github.begla.blockmania.generators.ChunkGeneratorMountain;
import com.github.begla.blockmania.generators.ChunkGeneratorResources;
import com.github.begla.blockmania.generators.ChunkGeneratorTerrain;
import com.github.begla.blockmania.generators.ObjectGeneratorPineTree;
import com.github.begla.blockmania.generators.ObjectGeneratorTree;
import com.github.begla.blockmania.utilities.FastRandom;
import com.github.begla.blockmania.utilities.VectorPool;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import org.jdom.JDOMException;
import static org.lwjgl.opengl.GL11.*;
import java.util.logging.Level;
import javolution.util.FastList;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.input.SAXBuilder;
import org.jdom.output.XMLOutputter;
import org.lwjgl.util.vector.Vector3f;
import org.newdawn.slick.opengl.Texture;
import org.newdawn.slick.opengl.TextureLoader;
import org.newdawn.slick.util.ResourceLoader;
import org.xml.sax.InputSource;

/**
 * The world of Blockmania. At its most basic the world contains chunks (consisting of a fixed amount of blocks)
 * and the player.
 *
 * The world is randomly generated by using a bunch of Perlin noise generators initialized
 * with a favored seed value.
 * 
 * @author Benjamin Glatzel <benjamin.glatzel@me.com>
 */
public final class World extends RenderableObject {

    /* ------ */
    private long _lastDaytimeMeasurement = Helper.getInstance().getTime();
    private long _latestDirtEvolvement = Helper.getInstance().getTime();
    private long _lastUpdateTime = Helper.getInstance().getTime();
    /* ------ */
    private static Texture _textureSun, _textureMoon;
    /* ------ */
    private short _time = 8;
    private byte _daylight = 16;
    private Player _player;
    private Vector3f _spawningPoint;
    /* ------ */
    private boolean _updatingEnabled = false;
    private boolean _updateThreadAlive = true;
    private final Thread _updateThread;
    /* ------ */
    private final ChunkUpdateManager _chunkUpdateManager = new ChunkUpdateManager(this);
    private final ChunkCache _chunkCache = new ChunkCache(this);
    /* ------ */
    private final ChunkGeneratorTerrain _generatorTerrain;
    private final ChunkGeneratorTerrain _generatorMountain;
    private final ChunkGeneratorForest _generatorForest;
    private final ChunkGeneratorResources _generatorResources;
    private final ChunkGeneratorLakes _generatorLakes;
    private final ChunkGeneratorFlora _generatorGrass;
    private final ObjectGeneratorTree _generatorTree;
    private final ObjectGeneratorPineTree _generatorPineTree;
    private final FastRandom _rand;
    /* ------ */
    private String _title, _seed;
    /* ----- */
    int _lastGeneratedChunkID = 0;
    /* ----- */
    private FastList<Chunk> _visibleChunks;

    /**
     * Initializes a new world for the single player mode.
     * 
     * @param title The title/description of the world
     * @param seed The seed string used to generate the terrain
     * @param p The player
     */
    public World(String title, String seed, Player p) {
        if (title == null) {
            throw new IllegalArgumentException("No title provided.");
        }

        if (title.isEmpty()) {
            throw new IllegalArgumentException("No title provided.");
        }

        if (seed == null) {
            throw new IllegalArgumentException("No seed provided.");
        }

        if (seed.isEmpty()) {
            throw new IllegalArgumentException("No seed provided.");
        }

        if (p == null) {
            throw new IllegalArgumentException("No player provided.");
        }

        this._player = p;
        this._title = title;
        this._seed = seed;

        // If loading failed accept the given seed
        if (!loadMetaData()) {
            // Generate the save directory if needed
            File dir = new File(getWorldSavePath());
            if (!dir.exists()) {
                dir.mkdirs();
            }

            saveMetaData();
        }

        // Init. generators
        _generatorTerrain = new ChunkGeneratorTerrain(seed);
        _generatorMountain = new ChunkGeneratorMountain(seed);
        _generatorForest = new ChunkGeneratorForest(seed);
        _generatorResources = new ChunkGeneratorResources(seed);
        _generatorLakes = new ChunkGeneratorLakes(seed);
        _generatorTree = new ObjectGeneratorTree(this, seed);
        _generatorPineTree = new ObjectGeneratorPineTree(this, seed);
        _generatorGrass = new ChunkGeneratorFlora(seed);


        // Init. random generator
        _rand = new FastRandom(seed.hashCode());

        resetPlayer();
        _visibleChunks = fetchVisibleChunks();

        updateDaylight();

        _updateThread = new Thread(new Runnable() {

            @Override
            public void run() {
                while (true) {
                    /*
                     * Checks if the thread should be killed.
                     */
                    if (!_updateThreadAlive) {
                        return;
                    }

                    /*
                     * Puts the thread to sleep 
                     * if updating is disabled.
                     */
                    if (!_updatingEnabled) {
                        synchronized (_updateThread) {
                            try {
                                _updateThread.wait();
                            } catch (InterruptedException ex) {
                                Helper.LOGGER.log(Level.SEVERE, ex.toString());
                            }
                        }
                    }

                    /*
                     * Update chunks queued for updating.
                     */
                    _chunkUpdateManager.updateChunks();


                    /*
                     * These updates do not need to be run every iteration.
                     */
                    if (Helper.getInstance().getTime() - _lastUpdateTime > 1000) {
                        // Update the the list of visible chunks
                        _visibleChunks = fetchVisibleChunks();
                        // Remove chunks which are out of range
                        _chunkUpdateManager.removeInvisibleChunkUpdates();

                        _lastUpdateTime = Helper.getInstance().getTime();
                    }


                    /*
                     * Update the time of day.
                     */
                    updateDaytime();

                    /*
                     * Evolve chunks.
                     */
                    replantDirt();

                }
            }
        });
    }

    /**
     * Stops the updating thread and writes all chunks to disk.
     */
    public void dispose() {
        Helper.LOGGER.log(Level.INFO, "Disposing world {0} and saving all chunks.", _title);

        synchronized (_updateThread) {
            _updateThreadAlive = false;
            _updateThread.notify();
        }

        saveMetaData();
        _chunkCache.writeAllChunksToDisk();
    }

    /**
     * Updates the time of the world. A day in Blockmania takes 12 minutes.
     */
    private void updateDaytime() {
        if (Helper.getInstance().getTime() - _lastDaytimeMeasurement >= 30000) {
            if (_chunkUpdateManager.updatesSize() == 0) {
                setTime((short) (_time + 1));
            } else {
                return;
            }

            _lastDaytimeMeasurement = Helper.getInstance().getTime();

            Helper.LOGGER.log(Level.INFO, "Updated daytime to {0}h.", _time);
        }
    }

    /**
     * 
     */
    private void updateDaylight() {
        if (_time >= 18 && _time < 20) {
            _daylight = (byte) (0.7f * Configuration.MAX_LIGHT);
        } else if (_time == 20) {
            _daylight = (byte) (0.5f * Configuration.MAX_LIGHT);
        } else if (_time == 21) {
            _daylight = (byte) (0.3f * Configuration.MAX_LIGHT);
        } else if (_time == 22 || _time == 23) {
            _daylight = (byte) (0.2f * Configuration.MAX_LIGHT);
        } else if (_time >= 0 && _time <= 5) {
            _daylight = (byte) (0.1f * Configuration.MAX_LIGHT);
        } else if (_time == 6) {
            _daylight = (byte) (0.3f * Configuration.MAX_LIGHT);
        } else if (_time == 7) {
            _daylight = (byte) (0.7f * Configuration.MAX_LIGHT);
        } else if (_time >= 8 && _time < 18) {
            _daylight = (byte) Configuration.MAX_LIGHT;
        }
    }

    /**
     * 
     */
    private void replantDirt() {
        // Pick one chunk for grass updates every 100 ms
        if (Helper.getInstance().getTime() - _latestDirtEvolvement > 100) {

            // Do NOT replant chunks when updates are queued...
            // And do NOT replant chunks during the night...
            if (_chunkUpdateManager.updatesSize() > 0 || isNighttime() || _visibleChunks.isEmpty()) {
                return;
            }

            Chunk c = _visibleChunks.get((int) (Math.abs(_rand.randomLong()) % _visibleChunks.size()));

            if (!c.isFresh() && !c.isDirty() && !c.isLightDirty()) {
                _generatorGrass.generate(c);
                _chunkUpdateManager.queueChunkForUpdate(c, false, false, false);
            }

            _latestDirtEvolvement = Helper.getInstance().getTime();
        }
    }

    /**
     * Queues all displayed chunks for updating.
     */
    public void updateAllChunks() {
        for (FastList.Node<Chunk> n = _visibleChunks.head(), end = _visibleChunks.tail(); (n = n.getNext()) != end;) {
            _chunkUpdateManager.queueChunkForUpdate(n.getValue(), false, true, false);
        }
    }

    /**
     * Init. the static resources.
     */
    public static void init() {
        try {
            Helper.LOGGER.log(Level.INFO, "Loading world textures...");
            _textureSun = TextureLoader.getTexture("png", ResourceLoader.getResource("com/github/begla/blockmania/images/sun.png").openStream(), GL_NEAREST);
            _textureMoon = TextureLoader.getTexture("png", ResourceLoader.getResource("com/github/begla/blockmania/images/moon.png").openStream(), GL_NEAREST);
            Helper.LOGGER.log(Level.INFO, "Finished loading world textures!");
        } catch (IOException ex) {
            Helper.LOGGER.log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Renders the world.
     */
    @Override
    public void render() {
        renderHorizon();
        renderChunks();
    }

    /**
     * Renders the horizon.
     */
    public void renderHorizon() {
        glPushMatrix();
        // Position the sun relatively to the player
        glTranslatef(_player.getPosition().x, Configuration.CHUNK_DIMENSIONS.y * 1.25f, Configuration.getSettingNumeric("V_DIST_Z") * Configuration.CHUNK_DIMENSIONS.z + _player.getPosition().z);

        // Disable fog
        glDisable(GL_FOG);

        glColor4f(1f, 1f, 1f, 1.0f);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glEnable(GL_TEXTURE_2D);

        if (isDaytime()) {
            _textureSun.bind();
        } else {
            _textureMoon.bind();
        }
        glBegin(GL_QUADS);
        glTexCoord2f(0.0f, 0.0f);
        glVertex3f(-Configuration.SUN_SIZE, Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glTexCoord2f(1.f, 0.0f);
        glVertex3f(Configuration.SUN_SIZE, Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glTexCoord2f(1.f, 1.0f);
        glVertex3f(Configuration.SUN_SIZE, -Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glTexCoord2f(0.f, 1.0f);
        glVertex3f(-Configuration.SUN_SIZE, -Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glEnd();
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_BLEND);

        glEnable(GL_FOG);
        glPopMatrix();
    }

    /**
     * 
     * @return 
     */
    public FastList<Chunk> fetchVisibleChunks() {
        FastList<Chunk> visibleChunks = new FastList<Chunk>();
        for (int x = -(Configuration.getSettingNumeric("V_DIST_X").intValue() / 2); x < (Configuration.getSettingNumeric("V_DIST_X").intValue() / 2); x++) {
            for (int z = -(Configuration.getSettingNumeric("V_DIST_Z").intValue() / 2); z < (Configuration.getSettingNumeric("V_DIST_Z").intValue() / 2); z++) {
                Chunk c = _chunkCache.loadOrCreateChunk(calcPlayerChunkOffsetX() + x, calcPlayerChunkOffsetZ() + z);
                if (c != null) {
                    // If this chunk was not visible, update it
                    if (!isChunkVisible(c)) {
                        _chunkUpdateManager.queueChunkForUpdate(c, false, true, true);
                    }
                    visibleChunks.add(c);
                }
            }
        }

        return visibleChunks;
    }

    /**
     * Renders all active chunks.
     */
    public void renderChunks() {
        for (FastList.Node<Chunk> n = _visibleChunks.head(), end = _visibleChunks.tail(); (n = n.getNext()) != end;) {
            n.getValue().render(false);
        }
        for (FastList.Node<Chunk> n = _visibleChunks.head(), end = _visibleChunks.tail(); (n = n.getNext()) != end;) {
            n.getValue().render(true);
        }
    }

    /**
     * Update all dirty display lists.
     */
    @Override
    public void update() {
        _chunkUpdateManager.updateDisplayLists();
    }

    /**
     * Returns the chunk position of a given coordinate.
     *
     * @param x The X-coordinate of the block
     * @return The X-coordinate of the chunk
     */
    private int calcChunkPosX(int x) {
        return (x / (int) Configuration.CHUNK_DIMENSIONS.x);
    }

    /**
     * Returns the chunk position of a given coordinate.
     *
     * @param z The Z-coordinate of the block
     * @return The Z-coordinate of the chunk
     */
    private int calcChunkPosZ(int z) {
        return (z / (int) Configuration.CHUNK_DIMENSIONS.z);
    }

    /**
     * Returns the internal position of a block within a chunk.
     *
     * @param x1 The X-coordinate of the block within the world
     * @param x2 The X-coordinate of the chunk within the world
     * @return The X-coordinate of the block within the chunk
     */
    private int calcBlockPosX(int x1, int x2) {
        x1 = x1 % (Configuration.getSettingNumeric("V_DIST_X").intValue() * (int) Configuration.CHUNK_DIMENSIONS.x);
        return (x1 - (x2 * (int) Configuration.CHUNK_DIMENSIONS.x));
    }

    /**
     * Returns the internal position of a block within a chunk.
     *
     * @param x1 The Z-coordinate of the block within the world
     * @param x2 The Z-coordinate of the chunk within the world
     * @return The Z-coordinate of the block within the chunk
     */
    private int calcBlockPosZ(int z1, int z2) {
        z1 = z1 % (Configuration.getSettingNumeric("V_DIST_Z").intValue() * (int) Configuration.CHUNK_DIMENSIONS.z);
        return (z1 - (z2 * (int) Configuration.CHUNK_DIMENSIONS.z));
    }

    /**
     * Places a block of a specific type at a given position and refreshes the 
     * corresponding light values.
     * 
     * @param x The X-coordinate
     * @param y The Y-coordinate
     * @param z The Z-coordinate
     * @param type The type of the block to set
     * @param update If set the affected chunk is queued for updating
     * @param overwrite  
     */
    public final void setBlock(int x, int y, int z, byte type, boolean update, boolean overwrite) {
        int chunkPosX = calcChunkPosX(x) % Configuration.getSettingNumeric("V_DIST_X").intValue();
        int chunkPosZ = calcChunkPosZ(z) % Configuration.getSettingNumeric("V_DIST_Z").intValue();

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = _chunkCache.loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));

        if (c == null) {
            return;
        }

        if (overwrite || c.getBlock(blockPosX, y, blockPosZ) == 0x0) {

            if (Block.getBlockForType(c.getBlock(blockPosX, y, blockPosZ)).isRemovable()) {
                c.setBlock(blockPosX, y, blockPosZ, type);
            }

            if (update) {
                /*
                 * Update sunlight.
                 */
                byte oldValue = getLight(x, y, z, Chunk.LIGHT_TYPE.SUN);
                c.calcSunlightAtLocalPos(blockPosX, blockPosZ, true);
                c.refreshLightAtLocalPos(blockPosX, y, blockPosZ, Chunk.LIGHT_TYPE.SUN);
                byte newValue = getLight(x, y, z, Chunk.LIGHT_TYPE.SUN);

                /*
                 * Spread sunlight.
                 */
                if (newValue > oldValue) {
                    c.spreadLight(blockPosX, y, blockPosZ, newValue, Chunk.LIGHT_TYPE.SUN);
                } else if (newValue < oldValue) {
                    // TODO: Unspread sunlight
                    //c.unspreadLight(blockPosX, y, blockPosZ, oldValue, Chunk.LIGHT_TYPE.BLOCK);
                }


                /*
                 * Spread light of block light sources.
                 */
                byte luminance = Block.getBlockForType(type).getLuminance();

                oldValue = getLight(x, y, z, Chunk.LIGHT_TYPE.BLOCK);
                c.setLight(blockPosX, y, blockPosZ, luminance, Chunk.LIGHT_TYPE.BLOCK);
                newValue = getLight(x, y, z, Chunk.LIGHT_TYPE.BLOCK);

                if (newValue > oldValue) {
                    c.spreadLight(blockPosX, y, blockPosZ, luminance, Chunk.LIGHT_TYPE.BLOCK);
                } else {
                    c.refreshLightAtLocalPos(blockPosX, y, blockPosZ, Chunk.LIGHT_TYPE.BLOCK);
                    // TODO: Unspread block light
                    //c.unspreadLight(blockPosX, y, blockPosZ, oldValue, Chunk.LIGHT_TYPE.BLOCK);
                }

                /*
                 * Finally queue the chunk and its neighbors for updating.
                 */
                _chunkUpdateManager.queueChunkForUpdate(c, true, false, false);
            }
        }
    }

    /**
     * 
     * @param pos
     * @return 
     */
    public final byte getBlockAtPosition(Vector3f pos) {
        return getBlock((int) (pos.x + 0.5f), (int) (pos.y + 0.5f), (int) (pos.z + 0.5f));
    }

    /**
     * Returns the block at the given position.
     *
     * @param x The X-coordinate
     * @param y The Y-coordinate
     * @param z The Z-coordinate
     * @return The type of the block
     */
    public final byte getBlock(int x, int y, int z) {
        int chunkPosX = calcChunkPosX(x) % Configuration.getSettingNumeric("V_DIST_X").intValue();
        int chunkPosZ = calcChunkPosZ(z) % Configuration.getSettingNumeric("V_DIST_Z").intValue();

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = _chunkCache.loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));

        if (c != null) {
            return c.getBlock(blockPosX, y, blockPosZ);
        }

        return -1;
    }

    /**
     * Returns true if the block is surrounded by blocks within the N4-neighborhood on the xz-plane.
     *
     * @param x The X-coordinate
     * @param y The Y-coordinate
     * @param z The Z-coordinate
     */
    public final boolean isBlockSurrounded(int x, int y, int z) {
        return (getBlock(x + 1, y, z) > 0 || getBlock(x - 1, y, z) > 0 || getBlock(x, y, z + 1) > 0 || getBlock(x, y, z - 1) > 0);
    }

    /**
     * 
     * @param x
     * @param y
     * @param z
     * @return 
     */
    public final boolean canBlockSeeTheSky(int x, int y, int z) {
        int chunkPosX = calcChunkPosX(x) % Configuration.getSettingNumeric("V_DIST_X").intValue();
        int chunkPosZ = calcChunkPosZ(z) % Configuration.getSettingNumeric("V_DIST_Z").intValue();

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);


        Chunk c = _chunkCache.loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));

        if (c != null) {
            return c.canBlockSeeTheSky(blockPosX, y, blockPosZ);
        }

        return true;
    }

    /**
     * Returns the light value at the given position.
     *
     * @param x The X-coordinate
     * @param y The Y-coordinate
     * @param z The Z-coordinate
     * @param type 
     * @return The light value
     */
    public final byte getLight(int x, int y, int z, Chunk.LIGHT_TYPE type) {
        int chunkPosX = calcChunkPosX(x) % Configuration.getSettingNumeric("V_DIST_X").intValue();
        int chunkPosZ = calcChunkPosZ(z) % Configuration.getSettingNumeric("V_DIST_Z").intValue();

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = _chunkCache.loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));

        if (c != null) {
            return c.getLight(blockPosX, y, blockPosZ, type);
        }

        return -1;
    }

    /**
     * 
     * @param x
     * @param y
     * @param z
     * @return 
     */
    public final float getRenderingLightValue(int x, int y, int z) {
        int chunkPosX = calcChunkPosX(x) % Configuration.getSettingNumeric("V_DIST_X").intValue();
        int chunkPosZ = calcChunkPosZ(z) % Configuration.getSettingNumeric("V_DIST_Z").intValue();

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = _chunkCache.loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));

        if (c != null) {
            return c.getRenderingLightValue(blockPosX, y, blockPosZ);
        }

        return -1;
    }

    /**
     * Sets the light value at the given position.
     *
     * @param x The X-coordinate
     * @param y The Y-coordinate
     * @param z The Z-coordinate
     * @param intens The light intensity value
     * @param type  
     */
    public void setLight(int x, int y, int z, byte intens, Chunk.LIGHT_TYPE type) {
        int chunkPosX = calcChunkPosX(x) % Configuration.getSettingNumeric("V_DIST_X").intValue();
        int chunkPosZ = calcChunkPosZ(z) % Configuration.getSettingNumeric("V_DIST_Z").intValue();

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);


        Chunk c = _chunkCache.loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));

        if (c != null) {
            c.setLight(blockPosX, y, blockPosZ, intens, type);
        }
    }

    /**
     * Recursive light calculation.
     * 
     * Too slow!
     * 
     * @param x
     * @param y
     * @param z
     * @param lightValue
     * @param depth
     * @param type  
     */
    public void spreadLight(int x, int y, int z, byte lightValue, int depth, Chunk.LIGHT_TYPE type) {
        int chunkPosX = calcChunkPosX(x) % Configuration.getSettingNumeric("V_DIST_X").intValue();
        int chunkPosZ = calcChunkPosZ(z) % Configuration.getSettingNumeric("V_DIST_Z").intValue();

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = _chunkCache.loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));
        if (c != null) {
            c.spreadLight(blockPosX, y, blockPosZ, lightValue, depth, type);
        }
    }

    /**
     * Recursive light calculation.
     * 
     * Too weird.
     * 
     * @param x
     * @param y
     * @param z
     * @param oldValue
     * @param depth
     * @param type  
     */
    public void unspreadLight(int x, int y, int z, byte oldValue, int depth, Chunk.LIGHT_TYPE type) {
        int chunkPosX = calcChunkPosX(x) % Configuration.getSettingNumeric("V_DIST_X").intValue();
        int chunkPosZ = calcChunkPosZ(z) % Configuration.getSettingNumeric("V_DIST_Z").intValue();

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = _chunkCache.loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));

        if (c != null) {
            c.unspreadLight(blockPosX, y, blockPosZ, oldValue, depth, type);
        }
    }

    /**
     * Returns the daylight value.
     * 
     * @return The daylight value
     */
    public float getDaylightAsFloat() {
        return _daylight / 16f;
    }

    /**
     * Returns the player.
     * 
     * @return The player
     */
    public Player getPlayer() {
        return _player;
    }

    /**
     * Returns the color of the daylight as a vector.
     * 
     * @return The daylight color
     */
    public Vector3f getDaylightColor() {
        return VectorPool.getVector(getDaylightAsFloat() * 0.65f, getDaylightAsFloat() * 0.85f, 0.95f * getDaylightAsFloat());
    }

    /**
     * Calculates the offset of the player relative to the spawning point.
     * 
     * @return The player offset on the x-axis
     */
    private int calcPlayerChunkOffsetX() {
        return (int) (_player.getPosition().x / Configuration.CHUNK_DIMENSIONS.x);
    }

    /**
     * Calculates the offset of the player relative to the spawning point.
     *
     * @return The player offset on the z-axis
     */
    private int calcPlayerChunkOffsetZ() {
        return (int) (_player.getPosition().z / Configuration.CHUNK_DIMENSIONS.z);
    }

    /**
     * Returns the vertices of a block at the given position.
     *
     * @param x
     * @param y
     * @param z
     * @return
     */
    public Vector3f[] verticesForBlockAt(int x, int y, int z) {
        Vector3f[] vertices = new Vector3f[8];

        vertices[0] = VectorPool.getVector(x - .5f, y - .5f, z - .5f);
        vertices[1] = VectorPool.getVector(x + .5f, y - .5f, z - .5f);
        vertices[2] = VectorPool.getVector(x + .5f, y + .5f, z - .5f);
        vertices[3] = VectorPool.getVector(x - .5f, y + .5f, z - .5f);

        vertices[4] = VectorPool.getVector(x - .5f, y - .5f, z + .5f);
        vertices[5] = VectorPool.getVector(x + .5f, y - .5f, z + .5f);
        vertices[6] = VectorPool.getVector(x + .5f, y + .5f, z + .5f);
        vertices[7] = VectorPool.getVector(x - .5f, y + .5f, z + .5f);

        return vertices;
    }

    /**
     * Calculates the intersection of a given ray originating from a specified point with
     * a block. Returns a list of intersections ordered by the distance to the player.
     *
     * @param x
     * @param y
     * @param z
     * @param origin
     * @param ray
     * @return Distance-ordered list of ray-face-intersections
     */
    public FastList<Intersection> rayBlockIntersection(int x, int y, int z, Vector3f origin, Vector3f ray) {
        /*
         * Ignore invisible blocks.
         */
        if (Block.getBlockForType(getBlock(x, y, z)).isBlockInvisible()) {
            return null;
        }

        FastList<Intersection> result = new FastList<Intersection>();

        /*
         * Fetch all vertices of the specified block.
         */
        Vector3f[] vertices = verticesForBlockAt(x, y, z);
        Vector3f blockPos = VectorPool.getVector(x, y, z);

        /*
         * Generate a new intersection for each side of the block.
         */

        // Front
        Intersection is = rayFaceIntersection(blockPos, vertices[0], vertices[3], vertices[2], origin, ray);
        if (is != null) {
            result.add(is);
        }

        // Back
        is = rayFaceIntersection(blockPos, vertices[4], vertices[5], vertices[6], origin, ray);
        if (is != null) {
            result.add(is);
        }

        // Left
        is = rayFaceIntersection(blockPos, vertices[0], vertices[4], vertices[7], origin, ray);
        if (is != null) {
            result.add(is);
        }

        // Right
        is = rayFaceIntersection(blockPos, vertices[1], vertices[2], vertices[6], origin, ray);
        if (is != null) {
            result.add(is);
        }

        // Top
        is = rayFaceIntersection(blockPos, vertices[3], vertices[7], vertices[6], origin, ray);
        if (is != null) {
            result.add(is);
        }

        // Bottom
        is = rayFaceIntersection(blockPos, vertices[0], vertices[1], vertices[5], origin, ray);
        if (is != null) {
            result.add(is);
        }

        /*
         * Sort the intersections by distance.
         */
        Collections.sort(result);
        return result;
    }

    /**
     * Calculates an intersection with the face of a block defined by 3 points.
     * 
     * @param blockPos The position of the block to intersect with
     * @param v0 Point 1
     * @param v1 Point 2
     * @param v2 Point 3
     * @param origin Origin of the intersection ray
     * @param ray Direction of the intersection ray
     * @return Ray-face-intersection
     */
    private Intersection rayFaceIntersection(Vector3f blockPos, Vector3f v0, Vector3f v1, Vector3f v2, Vector3f origin, Vector3f ray) {

        // Calculate the plane to intersect with
        Vector3f a = Vector3f.sub(v1, v0, null);
        Vector3f b = Vector3f.sub(v2, v0, null);
        Vector3f norm = Vector3f.cross(a, b, null);


        float d = -(norm.x * v0.x + norm.y * v0.y + norm.z * v0.z);

        /**
         * Calculate the distance on the ray, where the intersection occurs.
         */
        float t = -(norm.x * origin.x + norm.y * origin.y + norm.z * origin.z + d) / (Vector3f.dot(ray, norm));

        if (t < 0) {
            return null;
        }

        /**
         * Calc. the point of intersection.
         */
        Vector3f intersectPoint = VectorPool.getVector(ray.x * t, ray.y * t, ray.z * t);
        Vector3f.add(intersectPoint, origin, intersectPoint);

        if (intersectPoint.x >= v0.x && intersectPoint.x <= v2.x && intersectPoint.y >= v0.y && intersectPoint.y <= v2.y && intersectPoint.z >= v0.z && intersectPoint.z <= v2.z) {
            return new Intersection(blockPos, v0, v1, v2, d, t, origin, ray, intersectPoint);
        }

        return null;
    }

    /**
     * Displays some information about the world formatted as a string.
     * 
     * @return String with world information
     */
    @Override
    public String toString() {
        return String.format("world (cdl: %d, cn: %d, cache: %d, ud: %fs, seed: \"%s\", title: \"%s\")", _chunkUpdateManager.updatesDLSize(), _chunkUpdateManager.updatesSize(), _chunkCache.size(), _chunkUpdateManager.getMeanUpdateDuration() / 1000d, _seed, _title);
    }

    /**
     * Starts the updating thread.
     */
    public void startUpdateThread() {
        _updatingEnabled = true;
        _updateThread.start();
    }

    /**
     * Resumes the updating thread.
     */
    public void resumeUpdateThread() {
        _updatingEnabled = true;
        synchronized (_updateThread) {
            _updateThread.notify();
        }
    }

    /**
     * Safely suspends the updating thread.
     */
    public void suspendUpdateThread() {
        _updatingEnabled = false;
    }

    /**
     * Sets the time of the world.
     *
     * @param time The time to set
     */
    public void setTime(short time) {
        _time = (short) (time % 24);

        byte oldDaylight = _daylight;
        updateDaylight();

        if (_daylight != oldDaylight) {
            updateAllChunks();
        }
    }

    /**
     *
     * @return
     */
    public ObjectGeneratorPineTree getGeneratorPineTree() {
        return _generatorPineTree;
    }

    /**
     *
     * @return
     */
    public ObjectGeneratorTree getGeneratorTree() {
        return _generatorTree;
    }

    /**
     * Returns true if it is daytime.
     * @return
     */
    public boolean isDaytime() {
        if (_time > 6 && _time < 20) {
            return true;
        }
        return false;
    }

    /**
     * Returns true if it is nighttime.
     * 
     * @return
     */
    public boolean isNighttime() {
        return !isDaytime();
    }

    /**
     * Sets the title of the world.
     * 
     * @param _title The title of the world
     */
    public void setTitle(String _title) {
        this._title = _title;
    }

    /**
     * Returns the title of the world.
     * 
     * @return The title of the world
     */
    public String getTitle() {
        return _title;
    }

    /**
     *
     * @param x
     * @param z  
     */
    public void generateNewChunk(int x, int z) {
        Chunk c = _chunkCache.loadOrCreateChunk(x, z);

        if (c == null) {
            return;
        }

        c.generate();

        Chunk[] neighbors = c.loadOrCreateNeighbors();

        for (int i = 0; i < neighbors.length; i++) {
            if (neighbors[i] != null) {
                neighbors[i].generate();
            }
        }

        c.updateLight();
        c.writeChunkToDisk();
    }

    /**
     * 
     * @param x
     * @param z
     * @return 
     */
    public Chunk prepareNewChunk(int x, int z) {
        FastList<ChunkGenerator> gs = new FastList<ChunkGenerator>();
        gs.add(_generatorTerrain);
        gs.add(_generatorLakes);
        gs.add(_generatorMountain);
        gs.add(_generatorResources);
        gs.add(_generatorForest);

        // Generate a new chunk and return it
        Chunk c = new Chunk(this, VectorPool.getVector(x, 0, z), gs);
        return c;
    }

    /**
     * 
     * @param c
     * @return
     */
    public boolean isChunkVisible(Chunk c) {
        if (_visibleChunks == null) {
            return false;
        }

        return _visibleChunks.contains(c);
    }

    /**
     * 
     */
    public void printPlayerChunkPosition() {
        int chunkPosX = calcChunkPosX((int) _player.getPosition().x);
        int chunkPosZ = calcChunkPosX((int) _player.getPosition().z);
        System.out.println(_chunkCache.getChunkByKey(Helper.getInstance().cantorize(chunkPosX, chunkPosZ)));
    }

    /**
     * 
     * @return 
     */
    public int getAmountGeneratedChunks() {
        return _chunkUpdateManager.getAmountGeneratedChunks();
    }

    /**
     * 
     * @return 
     */
    public String getSeed() {
        return _seed;
    }

    /**
     * 
     * @return 
     */
    private Vector3f findSpawningPoint() {
        for (int xz = 1024;; xz++) {
            float height = _generatorTerrain.calcHeightMap(xz, xz) * 128f;

            if (height > 64) {
                return VectorPool.getVector(xz, height + 16, xz);
            }
        }
    }

    /**
     * Sets the spawning point to the player's current position.
     */
    public void setSpawningPoint() {
        _spawningPoint = new Vector3f(_player.getPosition());
    }

    /**
     * 
     */
    public void resetPlayer() {
        if (_spawningPoint == null) {
            _spawningPoint = findSpawningPoint();
            _player.resetPlayer();
            _player.setPosition(_spawningPoint);
        } else {
            _player.resetPlayer();
            _player.setPosition(_spawningPoint);
        }
    }

    /**
     * 
     * @return 
     */
    public String getWorldSavePath() {
        return String.format("SAVED_WORLDS/%s", _title);

    }

    /**
     * 
     * @return 
     */
    private boolean saveMetaData() {
        File f = new File(String.format("%s/Metadata.xml", getWorldSavePath()));

        try {
            f.createNewFile();
        } catch (IOException ex) {
            Helper.LOGGER.log(Level.SEVERE, null, ex);
        }

        Element root = new Element("World");
        Document doc = new Document(root);

        // Save the world metadata
        root.setAttribute("seed", _seed);
        root.setAttribute("title", _title);
        root.setAttribute("time", Short.toString(_time));

        // Save the player metadata
        Element player = new Element("Player");
        player.setAttribute("x", new Float(_player.getPosition().x).toString());
        player.setAttribute("y", new Float(_player.getPosition().y).toString());
        player.setAttribute("z", new Float(_player.getPosition().z).toString());
        root.addContent(player);


        XMLOutputter outputter = new XMLOutputter();
        FileOutputStream output = null;

        try {
            output = new FileOutputStream(f);

            try {
                outputter.output(doc, output);
            } catch (IOException ex) {
                Helper.LOGGER.log(Level.SEVERE, null, ex);
            }

            return true;
        } catch (FileNotFoundException ex) {
            Helper.LOGGER.log(Level.SEVERE, null, ex);
        }


        return false;
    }

    /**
     * 
     * @return 
     */
    private boolean loadMetaData() {
        File f = new File(String.format("%s/Metadata.xml", getWorldSavePath()));

        try {
            SAXBuilder sxbuild = new SAXBuilder();
            InputSource is = new InputSource(new FileInputStream(f));
            Document doc;
            try {
                doc = sxbuild.build(is);
                Element root = doc.getRootElement();
                Element player = root.getChild("Player");

                _seed = root.getAttribute("seed").getValue();
                _spawningPoint = VectorPool.getVector(Float.parseFloat(player.getAttribute("x").getValue()), Float.parseFloat(player.getAttribute("y").getValue()), Float.parseFloat(player.getAttribute("z").getValue()));
                _title = root.getAttributeValue("title");
                _time = Short.parseShort(root.getAttributeValue("time"));

                return true;

            } catch (JDOMException ex) {
                Helper.LOGGER.log(Level.SEVERE, null, ex);
            } catch (IOException ex) {
                Helper.LOGGER.log(Level.SEVERE, null, ex);
            }

        } catch (FileNotFoundException ex) {
            // Metadata.xml not present
        }

        return false;
    }

    /**
     * 
     * @return
     */
    public ChunkCache getChunkCache() {
        return _chunkCache;
    }

    /**
     * 
     * @return
     */
    public ChunkUpdateManager getChunkUpdateManager() {
        return _chunkUpdateManager;
    }
}
