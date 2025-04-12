package mod.maxammus.graphicsPlus;

import com.wurmonline.client.game.World;
import com.wurmonline.client.renderer.Color;
import com.wurmonline.client.renderer.DeferredRenderer;
import com.wurmonline.client.renderer.Matrix;
import com.wurmonline.client.renderer.WorldRender;
import com.wurmonline.client.renderer.backend.*;
import com.wurmonline.client.renderer.cell.CellRenderable;
import com.wurmonline.client.renderer.cell.CellRenderer;
import com.wurmonline.client.renderer.shaders.Program;
import com.wurmonline.client.renderer.shaders.ProgramBindings;
import com.wurmonline.client.renderer.shaders.Uniform;
import com.wurmonline.client.resources.textures.Texture;
import com.wurmonline.client.util.BufferUtil;
import org.gotti.wurmunlimited.modloader.ReflectionUtil;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL30.GL_R32F;

public class SSAO {
    private ProgramBindings ssaoBindings;
    private Program ssaoProgram;
    private FBO ssaoFbo;
    private Pipeline ssaoPipeline;
    private Queue ssaoQueue;
    private FloatBuffer ssaoBlurAxis;
    private FloatBuffer ssaoRadius;
    private FloatBuffer ssaoBias;
    private FloatBuffer ssaoIntensity;
    private Program ssaoBlurProgram;
    private ProgramBindings ssaoBlurBindings;
    private WorldRender worldRender;
    Matrix projectionMatrixWorld;

    public static SSAO getInstance() {
        if(instance == null)
            instance = new SSAO();
        return instance;
    }

    public static SSAO instance;

    public SSAO() {
        try {
            worldRender = CellRenderable.world.getWorldRenderer();
            createRenderTarget();

            ssaoBlurAxis = BufferUtil.newFloatBuffer(2);
            ssaoRadius = BufferUtil.newFloatBuffer(1);
            ssaoBias = BufferUtil.newFloatBuffer(1);
            ssaoIntensity = BufferUtil.newFloatBuffer(1);
            ssaoRadius.put(GraphicsPlus.ssaoRadius);
            ssaoBias.put(GraphicsPlus.ssaoBias);
            ssaoIntensity.put(GraphicsPlus.ssaoIntensity);
            ssaoRadius.rewind();
            ssaoBias.rewind();
            ssaoIntensity.rewind();

            ssaoBlurProgram = Program.load("program.modBlur");
            ssaoBlurBindings = new ProgramBindings();
            Uniform uniform = ssaoBlurProgram.getUniformByName("floatAxis");
            ssaoBlurBindings.bindUniformFloat(uniform, ssaoBlurAxis);
            uniform = ssaoBlurProgram.getUniformByName("source");
            ssaoBlurBindings.bindUniformSampler(uniform.getLocation(), 0);
            uniform = ssaoBlurProgram.getUniformByName("gDepth");
            ssaoBlurBindings.bindUniformSampler(uniform.getLocation(), 1);

            ssaoProgram = Program.load("program.modSSAO");
            ssaoBindings = new ProgramBindings();
            uniform = ssaoProgram.getUniformByName("gDepth");
            ssaoBindings.bindUniformSampler(uniform.getLocation(), 0);
            uniform = ssaoProgram.getUniformByName("gNormal");
            if(uniform != null) ssaoBindings.bindUniformSampler(uniform.getLocation(), 1);
            FloatBuffer resolution;
            Field resolutionBuffer = ReflectionUtil.getField(DeferredRenderer.class,"resolutionBuffer");
            resolution = ReflectionUtil.getPrivateField(worldRender.getDeferredRenderer(), resolutionBuffer);
            uniform = ssaoProgram.getUniformByName("resolution");
            ssaoBindings.bindUniformFloat(uniform, resolution);
            uniform = ssaoProgram.getUniformByName("radius");
            ssaoBindings.bindUniformFloat(uniform, ssaoRadius);
            uniform = ssaoProgram.getUniformByName("bias");
            ssaoBindings.bindUniformFloat(uniform, ssaoBias);
            uniform = ssaoProgram.getUniformByName("intensity");
            ssaoBindings.bindUniformFloat(uniform, ssaoIntensity);

            Field matrix = ReflectionUtil.getField(WorldRender.class,"projectionMatrixWorld");
            projectionMatrixWorld = ReflectionUtil.getPrivateField(worldRender, matrix);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public Texture renderSSAO() {
        Matrix ortho = new Matrix();
        ortho.orthoProjection(0.0f, 1.0f, 0.0f, 1.0f, -1.0f, 1.0f);
        ssaoQueue.setProjectionMatrix(ortho);
        ssaoQueue.setViewMatrix(null);

        Primitive p = ssaoQueue.reservePrimitive();
        p.copyStateFrom(RenderState.RENDERSTATE_DEFAULT);
        p.vertex = Primitive.staticVertexSquareVerticalFlip2D;
        p.type = Primitive.Type.TRIANGLESTRIP;
        p.num = 2;
        p.texture[0] = worldRender.getDeferredRenderer().getRenderTarget().getDepthTexture();
//        p.texture[1] = getRenderTarget().getNormalTexture();
        p.blendmode = Primitive.BlendMode.OPAQUE;
        p.depthtest = Primitive.TestFunc.ALWAYS;
        p.program = ssaoProgram;

        try {
            Field matrix = ReflectionUtil.getField(WorldRender.class,"projectionMatrixWorld");
            projectionMatrixWorld = ReflectionUtil.getPrivateField(worldRender, matrix);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        Uniform uniform = ssaoProgram.getUniformByName("projectionMatrix");
        ProgramBindings.UniformBindingFloat ubf;
        if(uniform != null) {
            ubf = ssaoBindings.bindUniformMatrix(uniform.getLocation(), uniform.getDimension(), uniform.getType());
            ubf.values = projectionMatrixWorld.getBuffer();
        }

        p.bindings = ssaoBindings;
        ssaoQueue.queue(p, null);

        ssaoPipeline.setViewport(0, 0, worldRender.getScreenWidth(), worldRender.getScreenHeight());
        ssaoPipeline.setTarget(ssaoFbo);
        ssaoPipeline.clear(true, false, Color.BLACK, 0.0f);
        ssaoPipeline.flush();
        ssaoQueue.clear();

        for (int blurPasses = 3, i = 0; i < blurPasses; ++i) {
            doBlur(ssaoPipeline, ssaoQueue);
        }
        return  ssaoFbo.getTexture();
    }
    private void doBlur(final Pipeline pipeline, final Queue queue) {
        final FBO fbo = (FBO)pipeline.getTarget();
        final Matrix ortho = new Matrix();
        ortho.orthoProjection(0.0f, 1.0f, 0.0f, 1.0f, -1.0f, 1.0f);
        queue.setProjectionMatrix(ortho);
        queue.setViewMatrix(null);

        Primitive p = queue.reservePrimitive();
        p.copyStateFrom(RenderState.RENDERSTATE_DEFAULT);
        p.texture[0] = fbo.getTexture();
        p.texture[1] = worldRender.getDeferredRenderer().getRenderTarget().getDepthTexture();
        p.texenv[0] = Primitive.TexEnv.MODULATE;
        p.num = 2;
        p.offset = 0;
        p.type = Primitive.Type.TRIANGLESTRIP;
        p.vertex = Primitive.staticVertexSquareVerticalFlip2D;
        p.program = ssaoBlurProgram;
        p.bindings = ssaoBlurBindings;

        queue.queue(p, null);
        pipeline.setViewport(0, 0, fbo.getWidth(), fbo.getHeight());
        pipeline.setTarget(fbo);

        ssaoBlurAxis.clear();
        ssaoBlurAxis.put(1.0f);
        ssaoBlurAxis.put(0.0f);
        pipeline.flush();

        ssaoBlurAxis.clear();
        ssaoBlurAxis.put(0.0f);
        ssaoBlurAxis.put(1.0f);
        pipeline.flush();

        queue.clear();
    }
    public void createRenderTarget() {
        if(ssaoFbo != null)
            ssaoFbo.delete();
        ssaoFbo = new FBO(worldRender.getScreenWidth(), worldRender.getScreenHeight(), false, true, false, false, false, GL_RED, GL_R32F);
        ssaoFbo.init();
        ssaoPipeline = new Pipeline(ssaoFbo);
        ssaoQueue = new Queue(1, false);
        ssaoPipeline.addQueue(0, ssaoQueue);
    }
}
