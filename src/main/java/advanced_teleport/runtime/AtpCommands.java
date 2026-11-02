package advanced_teleport.runtime;

import advanced_teleport.api.AdvancedTeleportApi;
import advanced_teleport.graph.GraphData.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.command.argument.DimensionArgumentType;
import net.minecraft.command.argument.Vec3ArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import java.util.*;
import static net.minecraft.server.command.CommandManager.*;

public final class AtpCommands {
    private record Draft(String id,String dimension,List<Point> vertices) {}
    private static final Map<UUID,Draft> DRAFTS=new HashMap<>();
    @FunctionalInterface private interface Action {int run() throws Exception;}
    private static int checked(Action action)throws CommandSyntaxException{
        try{return action.run();}catch(CommandSyntaxException e){throw e;}catch(Exception e){throw new SimpleCommandExceptionType(Text.literal("[ATP] "+e.getMessage())).create();}
    }
    private static AtpServer service(ServerCommandSource source){var service=AtpServer.get(source.getServer());if(service==null)throw new IllegalStateException("ATP 尚未加载");return service;}
    private static int feedback(ServerCommandSource source,String text){source.sendFeedback(()->Text.literal("[ATP] "+text),false);return 1;}
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        var root=literal("atp")
            .then(literal("status").executes(c->checked(()->{
                var service=service(c.getSource());var graph=service.snapshot();
                return feedback(c.getSource(),"模式="+service.config().mode+" 版本="+graph.revision()+" 城市="+graph.cities().size()+" 道路段="+graph.roads().size()+" 站点="+graph.stations().size()+" 传送门="+graph.portals().size());
            })))
            .then(literal("sync").executes(c->checked(()->{service(c.getSource()).sync(c.getSource().getPlayerOrThrow());return feedback(c.getSource(),"已请求同步。");})))
            .then(literal("reload").requires(s->s.hasPermissionLevel(2)).executes(c->checked(()->{service(c.getSource()).reload();return feedback(c.getSource(),"配置已重新加载。");})))
            .then(literal("mode").requires(s->s.hasPermissionLevel(2)).then(argument("mode",StringArgumentType.word())
                .suggests((c,b)->{b.suggest("default");b.suggest("strict");b.suggest("off");return b.buildFuture();})
                .executes(c->checked(()->{service(c.getSource()).setMode(StringArgumentType.getString(c,"mode"));return feedback(c.getSource(),"模式已更新。");}))))
            .then(literal("scan").requires(s->s.hasPermissionLevel(2)).executes(c->checked(()->{
                var player=c.getSource().getPlayerOrThrow();service(c.getSource()).enqueue(AtpServer.point(player).dimension(),player.getBlockPos());return feedback(c.getSource(),"已加入附近道路检测队列。");
            })))
            .then(literal("tp").requires(s->s.hasPermissionLevel(2)||(AtpServer.get(s.getServer())!=null&&AtpServer.get(s.getServer()).enabled()))
                .then(argument("dimension",DimensionArgumentType.dimension()).then(argument("location",Vec3ArgumentType.vec3()).executes(c->checked(()->{
                    var source=c.getSource();var player=source.getPlayerOrThrow();var world=DimensionArgumentType.getDimensionArgument(c,"dimension");var target=Vec3ArgumentType.getVec3(c,"location");
                    if(source.hasPermissionLevel(2)){player.teleport(world,target.x,target.y,target.z,player.getYaw(),player.getPitch());return 1;}
                    service(source).request(source,List.of(player),new Point(world.getRegistryKey().getValue().toString(),target.x,target.y,target.z));return 1;
                })))));
        var city=literal("city").requires(s->s.hasPermissionLevel(2));
        city.then(literal("begin").then(argument("id",StringArgumentType.word()).executes(c->checked(()->{
            var player=c.getSource().getPlayerOrThrow();String id=StringArgumentType.getString(c,"id");
            if(id.startsWith("auto:"))throw new IllegalArgumentException("auto: 为保留前缀");
            DRAFTS.put(player.getUuid(),new Draft(id,AtpServer.point(player).dimension(),new ArrayList<>()));return feedback(c.getSource(),"已开始城市 "+id+"；在各顶点使用 /atp city vertex，然后 save。");
        }))));
        city.then(literal("vertex").executes(c->checked(()->{
            var player=c.getSource().getPlayerOrThrow();var draft=DRAFTS.get(player.getUuid());
            if(draft==null)throw new IllegalArgumentException("请先 begin 一个城市");
            Point point=AtpServer.point(player);if(!point.dimension().equals(draft.dimension()))throw new IllegalArgumentException("城市顶点必须在同一维度");
            draft.vertices().add(point);return feedback(c.getSource(),"已添加顶点 "+draft.vertices().size());
        })));
        city.then(literal("save").executes(c->checked(()->{
            var player=c.getSource().getPlayerOrThrow();var draft=DRAFTS.get(player.getUuid());if(draft==null)throw new IllegalArgumentException("没有编辑中的城市");
            AdvancedTeleportApi.registerCity(c.getSource().getServer(),new City(draft.id(),draft.dimension(),draft.vertices(),true));DRAFTS.remove(player.getUuid());return feedback(c.getSource(),"城市已保存并自动闭合。");
        })));
        city.then(literal("cancel").executes(c->checked(()->{DRAFTS.remove(c.getSource().getPlayerOrThrow().getUuid());return feedback(c.getSource(),"已取消。");})));
        city.then(literal("remove").then(argument("id",StringArgumentType.word()).executes(c->checked(()->{
            boolean removed=AdvancedTeleportApi.unregister(c.getSource().getServer(),"city",StringArgumentType.getString(c,"id"));return feedback(c.getSource(),removed?"城市已删除。":"找不到该城市。");
        }))));
        city.then(literal("list").executes(c->checked(()->feedback(c.getSource(),String.join(", ",service(c.getSource()).snapshot().cities().stream().map(City::id).toList())))));
        root.then(city);dispatcher.register(root);
    }
    public static void disconnected(UUID player){DRAFTS.remove(player);}
    private AtpCommands(){}
}
