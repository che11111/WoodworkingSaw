package dev.che1s.woodworkingsaw;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可选的 CER API 适配器。
 *
 * <p>WoodworkingSaw 仍可独立安装在没有 CER 的服务器上，这里通过 CER 的
 * 插件类加载器加载公开 API。服务调用使用版本化 DTO，不读取 CER 内部实现。</p>
 */
final class CerRecipePublisher implements Listener {
    static final String PROVIDER_ID = "woodworkingsaw";
    static final String WORKSTATION_ID = WoodworkingSawPlugin.CUSTOM_ITEM_ID;

    private final WoodworkingSawPlugin plugin;
    private List<TransformService.Rule> rules = List.of();
    private Class<?> apiClass;
    private Class<?> registrationClass;
    private Object api;
    private Object registration;
    private boolean unavailableLogged;

    CerRecipePublisher(WoodworkingSawPlugin plugin) {
        this.plugin = plugin;
    }

    void publish(List<TransformService.Rule> rules) {
        this.rules = List.copyOf(rules);
        try {
            Object service = this.api();
            if (service == null) return;
            List<Object> recipes = this.recipes(this.rules);
            if (this.registration == null) {
                Method register = this.findMethod(service.getClass(), "register", 3);
                this.registration = register.invoke(service, this.plugin, PROVIDER_ID, recipes);
                this.plugin.getLogger().info(this.plugin.language().text(
                        "logs.cer-api-connected", "count", Integer.toString(recipes.size())));
            } else {
                Method replace = this.findMethod(this.registrationType(), "replace", 1);
                replace.invoke(this.registration, recipes);
            }
        } catch (ClassNotFoundException ignored) {
            // CER 不存在或版本过旧；WoodworkingSaw 的其他功能不受影响。
        } catch (ReflectiveOperationException | RuntimeException error) {
            Throwable cause = error instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause() : error;
            this.plugin.getLogger().warning(this.plugin.language().text(
                    "logs.cer-api-failed", "error", String.valueOf(cause.getMessage())));
        }
    }

    void shutdown() {
        if (this.registration == null) return;
        try {
            this.findMethod(this.registrationType(), "unregister", 0).invoke(this.registration);
        } catch (ReflectiveOperationException | RuntimeException error) {
            this.plugin.getLogger().warning(this.plugin.language().text(
                    "logs.cer-api-failed", "error", String.valueOf(error.getMessage())));
        } finally {
            this.registration = null;
            this.api = null;
            this.apiClass = null;
            this.registrationClass = null;
        }
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (!event.getPlugin().getName().equals("CraftEngineRecipe")) return;
        this.api = null;
        this.apiClass = null;
        this.registration = null;
        this.registrationClass = null;
        this.unavailableLogged = false;
        this.publish(this.rules);
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (!event.getPlugin().getName().equals("CraftEngineRecipe")) return;
        this.registration = null;
        this.registrationClass = null;
        this.api = null;
        this.apiClass = null;
    }

    private Object api() throws ClassNotFoundException, ReflectiveOperationException {
        if (this.api != null) return this.api;
        Plugin cer = Bukkit.getPluginManager().getPlugin("CraftEngineRecipe");
        if (cer == null || !cer.isEnabled()) return null;
        if (this.apiClass == null) {
            this.apiClass = Class.forName("dev.che1s.craftenginerecipe.api.CerRecipeApi",
                    false, cer.getClass().getClassLoader());
            int version = this.apiClass.getField("API_VERSION").getInt(null);
            if (version != 1) {
                throw new IllegalStateException("Unsupported CER API version " + version);
            }
        }
        this.api = Bukkit.getServicesManager().load(this.apiClass);
        if (this.api == null && !this.unavailableLogged) {
            this.unavailableLogged = true;
            this.plugin.getLogger().warning(this.plugin.language().text("logs.cer-api-unavailable"));
        }
        return this.api;
    }

    private Class<?> registrationType() throws ClassNotFoundException {
        if (this.registrationClass == null) {
            this.registrationClass = Class.forName(this.apiClass.getName() + "$Registration",
                    false, this.apiClass.getClassLoader());
        }
        return this.registrationClass;
    }

    private List<Object> recipes(List<TransformService.Rule> rules) throws ReflectiveOperationException {
        List<Object> recipes = new ArrayList<>(rules.size());
        Object station = this.apiItem(WORKSTATION_ID,
                localized("<gold>锯木台</gold>", "<gold>Saw Table</gold>"), List.of(), 1);
        for (TransformService.Rule rule : rules) {
            Object ingredient = this.apiItem(rule.source().getKey().toString(), null, List.of(),
                    rule.inputAmount());
            Object result = this.apiItem(rule.target().getKey().toString(), null, List.of(),
                    rule.outputAmount());
            Object note = this.note(rule);
            recipes.add(this.construct("ApiRecipe",
                    new Class<?>[]{String.class, this.apiClass("ApiItem"), List.class, List.class,
                            this.apiClass("ApiNote")},
                    recipeId(rule.source()), station, List.of(ingredient), List.of(result), note));
        }
        return recipes;
    }

    private Object note(TransformService.Rule rule) throws ReflectiveOperationException {
        Map<String, String> name = localized("锯木台转化", "Saw Table conversion");
        Map<String, String> lore = new LinkedHashMap<>();
        lore.put("zh_CN", "<gray>手持物品右键伐木台</gray>");
        lore.put("en_US", "<gray>Right-click the saw table while holding an item.</gray>");
        Map<String, String> amount = new LinkedHashMap<>();
        amount.put("zh_CN", "<gray>每次消耗 <white>" + rule.inputAmount()
                + "</white>，产出 <white>" + rule.outputAmount() + "</white>。</gray>");
        amount.put("en_US", "<gray>Consumes <white>" + rule.inputAmount()
                + "</white> and produces <white>" + rule.outputAmount() + "</white> per conversion.</gray>");
        return this.construct("ApiNote",
                new Class<?>[]{this.apiClass("LocalizedText"), List.class},
                this.localizedText(name), List.of(this.localizedText(lore), this.localizedText(amount)));
    }

    private Object apiItem(String id, Map<String, String> name, List<Object> lore, int amount)
            throws ReflectiveOperationException {
        Object localizedName = name == null ? null : this.localizedText(name);
        return this.construct("ApiItem",
                new Class<?>[]{String.class, this.apiClass("LocalizedText"), List.class, int.class},
                id, localizedName, lore, amount);
    }

    private Object localizedText(Map<String, String> values) throws ReflectiveOperationException {
        return this.construct("LocalizedText", new Class<?>[]{Map.class}, values);
    }

    private Object construct(String simpleName, Class<?>[] parameterTypes, Object... arguments)
            throws ReflectiveOperationException {
        return this.apiClass(simpleName).getConstructor(parameterTypes).newInstance(arguments);
    }

    private Class<?> apiClass(String simpleName) throws ClassNotFoundException {
        if (this.apiClass == null) throw new IllegalStateException("CER API class is not loaded");
        return Class.forName("dev.che1s.craftenginerecipe.api." + simpleName,
                false, this.apiClass.getClassLoader());
    }

    private Method findMethod(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) return method;
        }
        throw new NoSuchMethodException(type.getName() + "#" + name + "/" + parameterCount);
    }

    static String recipeId(Material material) {
        return "transform/" + material.getKey().getNamespace() + "/" + material.getKey().getKey();
    }

    private static Map<String, String> localized(String chinese, String english) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("zh_CN", chinese);
        values.put("en_US", english);
        return values;
    }
}
