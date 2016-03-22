/*
 * Copyright 2015 MovingBlocks
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
package org.teraslogy.simpleliquids.block.entity;

import com.google.common.collect.Queues;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.entitySystem.entity.EntityRef;
import org.terasology.entitySystem.event.EventPriority;
import org.terasology.entitySystem.event.ReceiveEvent;
import org.terasology.entitySystem.systems.BaseComponentSystem;
import org.terasology.entitySystem.systems.RegisterMode;
import org.terasology.entitySystem.systems.RegisterSystem;
import org.terasology.entitySystem.systems.UpdateSubscriberSystem;
import org.terasology.logic.health.DoDestroyEvent;
import org.terasology.math.Side;
import org.terasology.math.geom.Vector3i;
import org.terasology.registry.In;
import org.terasology.world.OnChangedBlock;
import org.terasology.world.WorldProvider;
import org.terasology.world.block.Block;
import org.terasology.world.block.BlockComponent;
import org.terasology.world.block.BlockManager;
import org.terasology.world.chunks.ChunkConstants;
import org.terasology.world.chunks.event.OnChunkGenerated;
import org.terasology.world.chunks.event.OnChunkLoaded;

/**
 * Event handler for events affecting block entities related to liquids such as water or lava.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class LiquidEntitySystem extends BaseComponentSystem implements UpdateSubscriberSystem {

    private static Logger logger = LoggerFactory.getLogger(LiquidEntitySystem.class);

    private static final int MAX_BLOCK_UPDATES = 500;

    @In
    private WorldProvider worldProvider;

    @In
    private BlockManager blockManager;

    private Block water;
    private Block air;

    private ConcurrentLinkedQueue<Vector3i> waterPositions;
    private float timeSinceLastUpdate;

    @Override
    public void initialise() {
        super.initialise();

        waterPositions = Queues.newConcurrentLinkedQueue();

        water = blockManager.getBlock("core:water");
        air = blockManager.getBlock(BlockManager.AIR_ID);
    }

    @ReceiveEvent(priority = EventPriority.PRIORITY_NORMAL)
    public void doDestroy(DoDestroyEvent event, EntityRef entity, BlockComponent blockComponent) {
        Vector3i blockLocation = blockComponent.getPosition();
        Vector3i neighborLocation;

        neighborLocation = new Vector3i(blockLocation);
        neighborLocation.add(Side.TOP.getVector3i());
        if (worldProvider.isBlockRelevant(neighborLocation)) {
            Block neighborBlock = worldProvider.getBlock(neighborLocation);
            if (neighborBlock == water) {
                if (!waterPositions.contains(blockLocation)) {
                    waterPositions.add(blockLocation);
                }
                return;
            }
        }

        for (Side side : Side.horizontalSides()) {
            neighborLocation = new Vector3i(blockLocation);
            neighborLocation.add(side.getVector3i());
            if (worldProvider.isBlockRelevant(neighborLocation)) {
                Block neighborBlock = worldProvider.getBlock(neighborLocation);
                if (neighborBlock == water) {
                    if (!waterPositions.contains(blockLocation)) {
                        waterPositions.add(blockLocation);
                    }
                    return;
                }
            }
        }
    }

    @ReceiveEvent(priority = EventPriority.PRIORITY_NORMAL)
    public void blockUpdate(OnChangedBlock event, EntityRef blockEntity) {
        Vector3i blockLocation = event.getBlockPosition();
        Vector3i neighborLocation;
        Block neighborBlock;

        if (event.getNewType().isLiquid()) {
            neighborLocation = new Vector3i(blockLocation);
            neighborLocation.add(Side.BOTTOM.getVector3i());
            if (worldProvider.isBlockRelevant(neighborLocation)) {
                neighborBlock = worldProvider.getBlock(neighborLocation);
                if ((neighborBlock == air || neighborBlock.isSupportRequired()) && !waterPositions.contains(neighborLocation)) {
                    // propagate down
                    waterPositions.add(neighborLocation);
                } else if (!neighborBlock.isLiquid() && neighborBlock != air) {
                    // spread to the sites
                    for (Side side : Side.horizontalSides()) {
                        neighborLocation = new Vector3i(blockLocation);
                        neighborLocation.add(side.getVector3i());
                        if (worldProvider.isBlockRelevant(neighborLocation)) {
                            neighborBlock = worldProvider.getBlock(neighborLocation);
                            if (neighborBlock == blockManager.getBlock(BlockManager.AIR_ID) || neighborBlock.isSupportRequired()) {
                                Vector3i neighborBottomLocation = new Vector3i(neighborLocation);
                                neighborBottomLocation.add(Side.BOTTOM.getVector3i());
                                if (!waterPositions.contains(neighborLocation)) {
                                    waterPositions.add(neighborLocation);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public void update(float delta) {
        timeSinceLastUpdate += delta;
        if (timeSinceLastUpdate >= 0.3f) {
            for (int i = 0; i < Math.min(waterPositions.size(), MAX_BLOCK_UPDATES); i++) {
                worldProvider.setBlock(waterPositions.poll(), water);
            }
            timeSinceLastUpdate -= 0.3f;
        }
    }

    @ReceiveEvent(priority = EventPriority.PRIORITY_NORMAL)
    public void onChunkGenerated(OnChunkGenerated chunkGenerated, EntityRef entity) {
        chunkWorker(chunkGenerated.getChunkPos(), entity);
    }

    @ReceiveEvent(priority = EventPriority.PRIORITY_NORMAL)
    public void onChunkLoaded(OnChunkLoaded chunkLoaded, EntityRef entity) {
        chunkWorker(chunkLoaded.getChunkPos(), entity);
    }

    public void chunkWorker(Vector3i chunkPos, EntityRef entity) {
        Vector3i worldPos = new Vector3i(chunkPos);
        worldPos.mul(ChunkConstants.SIZE_X, ChunkConstants.SIZE_Y, ChunkConstants.SIZE_Z);
        Vector3i blockPos = new Vector3i();
        Vector3i testPos = new Vector3i();
        // scan the chunk, looking for liquid
        for (int y = ChunkConstants.SIZE_Y - 1; y >= 0; y--) {
            for (int z = 0; z < ChunkConstants.SIZE_Z; z++) {
                for (int x = 0; x < ChunkConstants.SIZE_X; x++) {
                    blockPos.set(x + worldPos.x, y + worldPos.y, z + worldPos.z);
                    if (worldProvider.getBlock(blockPos).isLiquid()) {
                        // scan the neighboring blocks
                        for (Side side : Side.horizontalSides()) {
                            testPos.set(blockPos.x, blockPos.y, blockPos.z);
                            testPos.add(side.getVector3i());
                            // we only do this if we have air next to our liquid
                            if (worldProvider.getBlock(testPos) == air) {
                                blockUpdate(new OnChangedBlock(blockPos, water, water), worldProvider.getBlock(blockPos).getEntity());
                                break; // GET TO THE CHOPPAH!
                            }
                        }
                        // ___.___
                        //  c00D`=--/
                    }
                }
            }
        }

    }
}
