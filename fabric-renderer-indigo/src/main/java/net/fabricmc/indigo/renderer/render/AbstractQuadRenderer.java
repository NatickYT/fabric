/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.indigo.renderer.render;

import static net.fabricmc.indigo.renderer.helper.GeometryHelper.LIGHT_FACE_FLAG;

import java.util.function.Function;

import net.fabricmc.fabric.api.renderer.v1.render.RenderContext.QuadTransform;
import net.fabricmc.indigo.renderer.aocalc.AoCalculator;
import net.fabricmc.indigo.renderer.helper.ColorHelper;
import net.fabricmc.indigo.renderer.mesh.MutableQuadViewImpl;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderLayer;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.util.math.Matrix4f;
import net.minecraft.util.math.BlockPos;

/**
 * Base quad-rendering class for fallback and mesh consumers.
 * Has most of the actual buffer-time lighting and coloring logic.
 */
public abstract class AbstractQuadRenderer {
	static final int FULL_BRIGHTNESS = 0xF000F0;

	protected final Function<BlockRenderLayer, BufferBuilder> bufferFunc;
	protected final BlockRenderInfo blockInfo;
	protected final AoCalculator aoCalc;
	protected final QuadTransform transform;

	protected abstract Matrix4f matrix();
	
	AbstractQuadRenderer(BlockRenderInfo blockInfo, Function<BlockRenderLayer, BufferBuilder> bufferFunc, AoCalculator aoCalc, QuadTransform transform) {
		this.blockInfo = blockInfo;
		this.bufferFunc = bufferFunc;
		this.aoCalc = aoCalc;
		this.transform = transform;
	}

	/** handles block color and red-blue swizzle, common to all renders */
	private void colorizeQuad(MutableQuadViewImpl q, int blockColorIndex) {
		if (blockColorIndex == -1) {
			for (int i = 0; i < 4; i++) {
				q.spriteColor(i, 0, ColorHelper.swapRedBlueIfNeeded(q.spriteColor(i, 0)));
			}
		} else {
			final int blockColor = blockInfo.blockColor(blockColorIndex);
			for (int i = 0; i < 4; i++) {
				q.spriteColor(i, 0, ColorHelper.swapRedBlueIfNeeded(ColorHelper.multiplyColor(blockColor, q.spriteColor(i, 0))));
			}
		}
	}

	/** final output step, common to all renders */
	private void bufferQuad(MutableQuadViewImpl quad, BlockRenderLayer renderLayer) {
		bufferQuad(bufferFunc.apply(renderLayer), quad, matrix());
	}

	public static void bufferQuad(BufferBuilder buff, MutableQuadViewImpl quad, Matrix4f matrix) {
		for (int i = 0; i < 4; i++) {
			buff.method_22918(matrix, quad.x(i), quad.y(i), quad.z(i));
			final int color = quad.spriteColor(i, 0);
			buff.color(color & 0xFF, (color >> 8) & 0xFF, (color >> 16) & 0xFF, (color >> 24) & 0xFF);
			buff.method_22913(quad.spriteU(i, 0), quad.spriteV(i, 0));
			buff.method_22916(quad.lightmap(i));
	        buff.method_22914(quad.normalX(i),quad.normalY(i), quad.normalZ(i));
	        buff.next();
		}
	}
	
	// routines below have a bit of copy-paste code reuse to avoid conditional execution inside a hot loop

	/** for non-emissive mesh quads and all fallback quads with smooth lighting*/
	protected void tesselateSmooth(MutableQuadViewImpl q, BlockRenderLayer renderLayer, int blockColorIndex) {
		colorizeQuad(q, blockColorIndex);

		for (int i = 0; i < 4; i++) {
			q.spriteColor(i, 0, ColorHelper.multiplyRGB(q.spriteColor(i, 0), aoCalc.ao[i]));
			q.lightmap(i, ColorHelper.maxBrightness(q.lightmap(i), aoCalc.light[i]));
		}

		bufferQuad(q, renderLayer);
	}

	/** for emissive mesh quads with smooth lighting*/
	protected void tesselateSmoothEmissive(MutableQuadViewImpl q, BlockRenderLayer renderLayer, int blockColorIndex) {
		colorizeQuad(q, blockColorIndex);

		for (int i = 0; i < 4; i++) {
			q.spriteColor(i, 0, ColorHelper.multiplyRGB(q.spriteColor(i, 0), aoCalc.ao[i]));
			q.lightmap(i, FULL_BRIGHTNESS);
		}

		bufferQuad(q, renderLayer);
	}

	/** for non-emissive mesh quads and all fallback quads with flat lighting*/
	protected void tesselateFlat(MutableQuadViewImpl quad, BlockRenderLayer renderLayer, int blockColorIndex) {
		colorizeQuad(quad, blockColorIndex);
		final int brightness = flatBrightness(quad, blockInfo.blockState, blockInfo.blockPos);

		for (int i = 0; i < 4; i++) {
			quad.lightmap(i, ColorHelper.maxBrightness(quad.lightmap(i), brightness));
		}

		bufferQuad(quad, renderLayer);
	}

	/** for emissive mesh quads with flat lighting*/
	protected void tesselateFlatEmissive(MutableQuadViewImpl quad, BlockRenderLayer renderLayer, int blockColorIndex) {
		colorizeQuad(quad, blockColorIndex);

		for (int i = 0; i < 4; i++) {
			quad.lightmap(i, FULL_BRIGHTNESS);
		}

		bufferQuad(quad, renderLayer);
	}

	private final BlockPos.Mutable mpos = new BlockPos.Mutable();

	/** 
	 * Handles geometry-based check for using self brightness or neighbor brightness.
	 * That logic only applies in flat lighting.
	 */
	int flatBrightness(MutableQuadViewImpl quad, BlockState blockState, BlockPos pos) {
		mpos.set(pos);

		if ((quad.geometryFlags() & LIGHT_FACE_FLAG) != 0 || Block.isShapeFullCube(blockState.getCollisionShape(blockInfo.blockView, pos))) {
			mpos.setOffset(quad.lightFace());
		}

		// Unfortunately cannot use brightness cache here unless we implement one specifically for flat lighting. See #329
		return blockInfo.blockView.getLightmapIndex(blockState, mpos);
	}
}