package mod.maxammus.graphicsPlus;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;
import javassist.*;
import javassist.bytecode.DuplicateMemberException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.Configurable;
import org.gotti.wurmunlimited.modloader.interfaces.Initable;
import org.gotti.wurmunlimited.modloader.interfaces.WurmClientMod;
import org.gotti.wurmunlimited.modsupport.packs.ModPacks;

import java.io.File;

public class GraphicsPlus implements WurmClientMod, Configurable, Initable {
    static Logger logger = Logger.getLogger(mod.maxammus.graphicsPlus.GraphicsPlus.class.getName());
    private static String version = "0.1.1";
    ClassPool classPool = HookManager.getInstance().getClassPool();

    public static Map<String, List<String>> shaderEdits = new HashMap<>();

    public static boolean useSSAO = true;
    public static float ssaoRadius = 0.7f;
    public static float ssaoBias = 0.0f;
    public static float ssaoIntensity = 0.5f;
    public static SSAO ssao;
    @Override
    public void configure(Properties properties) {
        useSSAO = Boolean.parseBoolean(properties.getProperty("useSSAO", Boolean.toString(useSSAO)));
        ssaoRadius = Float.parseFloat(properties.getProperty("ssaoRadius", Float.toString(ssaoRadius)));
        ssaoBias = Float.parseFloat(properties.getProperty("ssaoBias", Float.toString(ssaoBias)));
        ssaoIntensity = Float.parseFloat(properties.getProperty("ssaoIntensity", Float.toString(ssaoIntensity)));

        logger.info("useSSAO: " + useSSAO);
        logger.info("ssaoRadius: " + ssaoRadius);
        logger.info("ssaoBias: " + ssaoBias);
        logger.info("ssaoIntensity: " + ssaoIntensity);

    }

    @Override
    public void init() {
        try {
            //load resources at some random point after init()
            classPool.getMethod("com.wurmonline.client.WurmClientBase", "runGame")
                            .insertBefore("mod.maxammus.graphicsPlus.GraphicsPlus.addResourcesAsPack();");

            logger.info("Fixing invisible creatures on modern renderer");
            addShaderEdit("shader.forward_dirlight.fragment", "col.a < 0.8", "col.a < 0.2");
            classPool.getMethod("com.wurmonline.client.renderer.shaders.Shader", "compile")
                    .instrument(new ExprEditor() {
                        @Override
                        public void edit(MethodCall m) throws CannotCompileException {
                            if(m.getMethodName().equals("split"))
                                m.replace(" { java.util.List edits = mod.maxammus.graphicsPlus.GraphicsPlus.shaderEdits.get(name);" +
                                        "java.lang.String toRet = shaderSource;" +
                                        "if(edits != null)" +
                                        "    for(int i = 0; i < edits.size() - 1; i += 2)" +
                                        "        toRet = toRet.replace((java.lang.String)edits.get(i), (java.lang.String)edits.get(i + 1));" +
                                        "$_ = toRet.split($$);}");
                        }
                    });

            logger.info("Fixing altar light beam visibility on modern renderer");
            CtClass lightBeamEffect = classPool.getCtClass("com.wurmonline.client.renderer.effects.LightBeamEffect");
            CtClass materialInstance = classPool.getCtClass("com.wurmonline.client.renderer.MaterialInstance");
            CtField lightBeamEffectMaterial = new CtField(materialInstance, "material", lightBeamEffect);
            try {
                lightBeamEffect.addField(lightBeamEffectMaterial, "material = com.wurmonline.client.util.GLHelper.useDeferredShading() " +
                        "? com.wurmonline.client.renderer.Material.load(\"material.simple\").instance() : null;");
                classPool.getMethod("com.wurmonline.client.renderer.effects.LightBeamEffect", "render")
                        .instrument(new ExprEditor() {
                            @Override
                            public void edit(MethodCall m) throws CannotCompileException {
                                if (m.getMethodName().equals("queue"))
                                    m.replace(" { if(material != null) {" +
                                            "    p.materialInstance = material;" +
                                            "    p.program = material.getProgram();" +
                                            "    p.bindings = material.getProgramBindings();" +
                                            "} $proceed($$);}");
                            }
                        });

            }
            //Prevent conflict with fixVBO already adding handling this
            //should work as long as mods are loaded alphabetically
            catch (DuplicateMemberException ignored)  { }

            if(useSSAO) {
                logger.info("Enabling SSAO");
                //Add SSAO texture to lighting pass
                classPool.getMethod("com.wurmonline.client.renderer.DeferredRenderer", "performLightingPass")
                        .instrument(new ExprEditor() {
                            @Override
                            public void edit(MethodCall m) throws CannotCompileException {
                                if (m.getMethodName().equals("getWhite"))
                                    m.replace("$_ = mod.maxammus.graphicsPlus.SSAO.getInstance().renderSSAO();");
                            }
                        });
                //change FBO size when window changes
                classPool.getMethod("com.wurmonline.client.renderer.DeferredRenderer", "sizeChanged")
                        .insertAfter("mod.maxammus.graphicsPlus.SSAO.getInstance().createRenderTarget();");
            }
        } catch (NotFoundException | CannotCompileException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getVersion() {
        return version;
    }
    public static void addResourcesAsPack() {
        Path jarPath = Paths.get("mods", "graphicsPlus", "GraphicsPlus.jar");
        File jarFile = jarPath.toFile();
        if(!jarFile.exists())
            throw new RuntimeException("Couldn't load resources from mods/graphicsPlus/GraphicsPlus.jar");
        //Add resources in mod jar to the client's resource list to
        // let vanilla shader system find modded shader files
        logger.info("Adding resources to client's packs");
        ModPacks.addPack(jarFile, null);
    }

    void addShaderEdit(String name, String target, String replace) {
        List<String> edits = shaderEdits.get(name);
        if(edits == null) {
            edits = new ArrayList<>();
            shaderEdits.put(name, edits);
        }
        edits.add(target);
        edits.add(replace);
    }
}