# Advanced Teleport 注册 API

ATP 提供服务端 Java API，供站点模组、城市模组和特殊道路模组注册交通数据。服务端入口是 `advanced_teleport.api.AdvancedTeleportApi`；客户端站点提交入口是 `advanced_teleport.api.AdvancedTeleportClientApi`。客户端提交需要通过接入模组安装在服务端的校验器，不能自行授予保护标记。

## 依赖与调用时机

在接入模组的 `fabric.mod.json` 中声明 ATP 依赖：

```json
{
  "depends": {
    "minecraft": "~1.20.4",
    "fabric-api": "*",
    "advanced_teleport": ">=1.0.1"
  }
}
```

本地开发可将构建后的 ATP JAR 放入接入工程的 `libs`，由 Fabric Loom 处理命名空间映射：

```groovy
dependencies {
    modImplementation files('libs/advanced_teleport-1.0.1.jar')
}
```

图数据的读取、注册和删除需要在 ATP 完成世界初始化后调用，例如服务端命令回调或正常运行中的 tick 回调。这些调用要求 **Minecraft 服务端线程**；异步任务先用 `server.execute(() -> { ... })` 切换线程。`registerStationProvider` 在接入模组的 common 初始化阶段注册一次校验器；校验器执行时位于服务器线程。客户端提交方法要求客户端线程。单机也使用集成服务端的权威图。

## 入口

| 调用 | 行为 |
| --- | --- |
| `registerCity(server, City)` | 注册或更新城市 |
| `registerRoad(server, Road)` | 注册或更新道路 |
| `registerStation(server, Station)` | 注册或更新站点，并验证道路存在及站点在线上 |
| `registerPortal(server, PortalLink)` | 注册或更新显式配对的传送门 |
| `unregister(server, type, id)` | 显式删除记录；`type` 为 `city`、`road`、`station` 或 `portal` |
| `snapshot(server)` | 返回不可变的当前 `GraphData.Snapshot` |
| `registerStationProvider(provider, validator)` | common 初始化时注册站点提交处理器；相同处理器 ID 不可重复注册 |
| `AdvancedTeleportClientApi.requestStationRegistration(provider, station)` | 客户端发送站点提案；返回 `true` 仅表示已发送，不表示已获准注册 |

注册是按类型和 ID 执行的 upsert。内容没有变化时不增加版本；一次有效变更只增加一次路网版本，随后由 ATP 保存并同步。调用失败会抛出异常，接入模组应向管理员给出可理解的错误信息。

推荐 ID 采用 `你的模组ID:本地名称`。**`auto:` 为保留前缀**，公共注册 API 拒绝使用它。不同世界各自保存这些 ID；不要把另一个世界的快照直接复制为当前世界状态。

## 数据类型

所有模型均为 `advanced_teleport.graph.GraphData` 内的公开 Java record：

```java
Point(String dimension, double x, double y, double z)
City(String id, String dimension, List<Point> vertices, boolean protectedEntry)
Road(String id, String dimension, List<Point> points, RoadKind kind, boolean protectedEntry)
Station(String id, String roadId, Point position, boolean protectedEntry)
PortalLink(String id, Point first, Point second, boolean protectedEntry)
PortalLink(String id, Point first, Point second, boolean protectedEntry,
           Point firstExtent, Point secondExtent)
Snapshot(long revision, List<City> cities, List<Road> roads,
         List<Station> stations, List<PortalLink> portals)
```

`RoadKind` 的取值为 `RAIL`、`ICE`、`API`。特殊模组道路通常使用 `API`；类型不改变几何连通规则。

- `dimension` 使用完整维度 ID，如 `minecraft:overworld`、`minecraft:the_nether`。坐标必须为有限数值。
- 城市至少 3 个同维度顶点，按边界顺序给出；闭合边自动连接最后和第一个顶点，不需要重复首点。城市范围使用 X/Z，多边形顶点的 Y 不构成高度边界。
- 道路至少 2 个同维度点，依次形成三维折线。整条注册道路作为一个路网对象参与连通，不要求自动检测对应方块。
- 站点以 `roadId` 指定道路，必须与该道路同维度，并在折线上或离折线不超过 0.1 格。普通站点最好放在有安全落脚空间的位置。
- 传送门两端可以处于不同维度。每端由起点和 `Extent` 对角点表示轴对齐的平面矩形，同一端的两个点必须处于同维度，至少一个坐标轴保持相同。道路按到整个门面的最短距离与 `portalConnectionDistance` 比较。
- 旧的四参数 `PortalLink` 构造器继续可用，两个 `Extent` 默认为相应端点，表示点状设施。已有 SQLite v1 数据迁移后也先保留这种点几何；自动发现的原版门随后会更新实际门面。
- 模型会复制传入列表；快照与列表不可变。不要通过反射或修改数据库绕过 API。

## 保护标记

公共 API 会把注册记录的 `protectedEntry` **强制设为 `true`**，即使调用者传入 `false`。自动道路扫描、玩家拆除方块和自动传送门失效不会删除这些受保护记录。

保护标记不限制显式 API 删除。接入模组拥有自己的设施生命周期时，应在设施失效后调用 `unregister`。如果删除道路，受保护站点仍保存；道路不存在或站点不再在线上时，该站点不会参与严格模式。需要彻底撤销设施时，先删除自己的站点，再删除道路。

可以给自动道路注册受保护站点：先从 `snapshot(server).roads()` 查找对应道路 ID，再用自己的站点 ID 调用 `registerStation`。自动道路通常由短线段组成，站点必须位于所引用的那一段上；道路被重新识别为不同 ID 时，接入模组应重新关联站点。

## 示例：注册城市、道路与站点

以下示例可放在接入模组的服务端命令处理方法中，在服务器线程调用。坐标仅用于展示，实际站点应与世界中的安全地面对应。

```java
import advanced_teleport.api.AdvancedTeleportApi;
import advanced_teleport.graph.GraphData.*;
import net.minecraft.server.MinecraftServer;
import java.util.List;

public final class DemoNetwork {
    private static final String OVERWORLD = "minecraft:overworld";

    private static Point point(double x, double y, double z) {
        return new Point(OVERWORLD, x, y, z);
    }

    public static void register(MinecraftServer server) {
        AdvancedTeleportApi.registerCity(server, new City(
            "example:west_city", OVERWORLD,
            List.of(point(0, 64, 0), point(40, 64, 0),
                    point(40, 64, 40), point(0, 64, 40)),
            true
        ));

        AdvancedTeleportApi.registerRoad(server, new Road(
            "example:main_line", OVERWORLD,
            List.of(point(20, 64, 20), point(120, 64, 20)),
            RoadKind.API, true
        ));

        AdvancedTeleportApi.registerStation(server, new Station(
            "example:west_station", "example:main_line", point(20, 64, 20), true
        ));
        AdvancedTeleportApi.registerStation(server, new Station(
            "example:east_station", "example:main_line", point(120, 64, 20), true
        ));
    }

    public static void remove(MinecraftServer server) {
        AdvancedTeleportApi.unregister(server, "station", "example:west_station");
        AdvancedTeleportApi.unregister(server, "station", "example:east_station");
        AdvancedTeleportApi.unregister(server, "road", "example:main_line");
        AdvancedTeleportApi.unregister(server, "city", "example:west_city");
    }
}
```

这个示例中，折线的一部分位于城市内，因此道路与城市连通。城市边界外的站点仍属于同一连通网络；无需把道路每个点都放进城市。

## 示例：注册跨维度连接

```java
AdvancedTeleportApi.registerPortal(server, new PortalLink(
    "example:custom_gate",
    new Point("minecraft:overworld", 120, 64, 20),
    new Point("minecraft:the_nether", 15, 70, 2.5),
    true
));
```

两端附近都需要有道路，才能将两个维度的路网连接起来。这个 API 用于接入模组已经知道端点的自定义传送设施。自动发现的下界门会按原版规则查找现有出口，并在实际穿门后确认；只注册连接不会自动建造方块或生成落脚平台。

宽门应提供门面的对角点，例如：

```java
AdvancedTeleportApi.registerPortal(server, new PortalLink(
    "example:wide_gate",
    new Point("minecraft:overworld", 120.5, 64, 20.5),
    new Point("minecraft:the_nether", 15.5, 70, 2.5),
    true,
    new Point("minecraft:overworld", 139.5, 68, 20.5),
    new Point("minecraft:the_nether", 17.5, 74, 2.5)
));
```

## 示例：客户端提交，服务端验证站点

下面是可放入接入模组的两端入口示例。它固定注册一条主世界 API 路线，并将位于路线上方的磁石作为站牌：磁石位于 `Y=63、Z=0、X=0..100`，站点中心位于其上方 `Y=64`。管理员潜行右键磁石时，客户端提交提案，服务端核验实际方块、距离、管理权限及绑定提交者 UUID 的站点 ID。

该示例没有把校验器写成无条件通过。实际允许普通玩家建设时，应把 OP 检查替换为服务器保存的地权或站点方块实体拥有者检查；客户端提供的名字、坐标和保护标记都不能代替这些事实。

common 入口 `example.stations.StationDemo`：

```java
package example.stations;

import advanced_teleport.api.AdvancedTeleportApi;
import advanced_teleport.graph.GraphData.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.Blocks;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import java.util.List;

public final class StationDemo implements ModInitializer {
    public static final Identifier PROVIDER = new Identifier("station_demo", "lodestone");
    public static final String ROAD = "station_demo:main_line";

    @Override public void onInitialize() {
        AdvancedTeleportApi.registerStationProvider(PROVIDER, (player, proposed) -> {
            var world = player.getServerWorld();
            Point p = proposed.position();
            if (!player.hasPermissionLevel(2)
                    || !p.dimension().equals(world.getRegistryKey().getValue().toString())) return false;
            BlockPos marker = BlockPos.ofFloored(p.x(), p.y() - 1, p.z());
            if (!world.isChunkLoaded(marker)
                    || !world.getBlockState(marker).isOf(Blocks.LODESTONE)) return false;
            if (Math.abs(p.x() - (marker.getX() + 0.5)) > 1e-6
                    || Math.abs(p.y() - (marker.getY() + 1.0)) > 1e-6
                    || Math.abs(p.z() - (marker.getZ() + 0.5)) > 1e-6) return false;
            if (player.squaredDistanceTo(p.x(), p.y(), p.z()) > 36) return false;
            String ownedId = "station_demo:" + player.getUuid() + "/" + marker.asLong();
            return proposed.id().equals(ownedId) && proposed.roadId().equals(ROAD);
            // ATP then verifies that ROAD exists and the station is actually on its polyline.
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server ->
            AdvancedTeleportApi.registerRoad(server, new Road(
                ROAD, "minecraft:overworld",
                List.of(new Point("minecraft:overworld", 0.5, 64, 0.5),
                        new Point("minecraft:overworld", 100.5, 64, 0.5)),
                RoadKind.API, true
            ))
        );
    }
}
```

client 入口 `example.stations.client.StationDemoClient`：

```java
package example.stations.client;

import advanced_teleport.api.AdvancedTeleportClientApi;
import advanced_teleport.graph.GraphData.Point;
import advanced_teleport.graph.GraphData.Station;
import example.stations.StationDemo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;

public final class StationDemoClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient || hand != Hand.MAIN_HAND || !player.isSneaking()
                    || !world.getBlockState(hit.getBlockPos()).isOf(Blocks.LODESTONE))
                return ActionResult.PASS;
            var marker = hit.getBlockPos();
            Point point = new Point(world.getRegistryKey().getValue().toString(),
                marker.getX() + 0.5, marker.getY() + 1.0, marker.getZ() + 0.5);
            Station station = new Station(
                "station_demo:" + player.getUuid() + "/" + marker.asLong(),
                StationDemo.ROAD, point, false
            );
            boolean sent = AdvancedTeleportClientApi.requestStationRegistration(
                StationDemo.PROVIDER, station
            );
            player.sendMessage(Text.literal(sent
                ? "站点请求已发送，等待服务端验证。"
                : "服务端没有提供兼容的 ATP 站点提交通道。"), true);
            return ActionResult.SUCCESS;
        });
    }
}
```

接入模组的 `fabric.mod.json` 至少包含以下对应项，并在客户端和服务端都安装接入模组：

```json
{
  "schemaVersion": 1,
  "id": "station_demo",
  "version": "1.0.0",
  "environment": "*",
  "entrypoints": {
    "main": ["example.stations.StationDemo"],
    "client": ["example.stations.client.StationDemoClient"]
  },
  "depends": {
    "fabricloader": ">=0.16.10",
    "minecraft": "~1.20.4",
    "java": ">=17",
    "fabric-api": "*",
    "advanced_teleport": ">=1.0.1"
  }
}
```

ATP 先检查处理器是否存在、站点 ID 是否属于处理器命名空间，再调用 `mayRegister(player, proposed)`；随后验证站点在线上、容量等图约束。接受后强制设为受保护记录，保存并同步给其他玩家。拒绝原因由服务端反馈。`requestStationRegistration` 的返回值仅表示发送成功，不能当作授权结果。

## 连通与传送规则

城市与城市不因为重叠自动合并；道路穿过多边形边界也能建立城市连接，不要求折线顶点正好落在城市内。道路间按三维线段最短距离判断，避免只比较端点而漏掉交叉处。连通关系可沿道路、城市和配对门传递。

`default` 选择起点连通范围内、目标维度中最接近目标的几何位置。`strict` 要求起点靠近一个有效站点，选择起点网络中距目标网络最近的站点；它不会直接跳到一个原本不属于起点网络的目标站点。距离相同时，比较到请求目标的最短传送门路线：同维度使用直线距离，跨门代价为 0。这不是沿铁路行驶的里程。

两种模式都由服务端独立复算。默认 `safeLanding=false`，保留精确图坐标；管理员可以显式启用安全落点搜索。朝向和 `facing` 由原版命令语法传入后保留。

同步使用压缩全量图和版本化增量。服务端保存改动记录的事务由后台线程执行，正常停止时等待最新保存完成；客户端缓存会被读取，但本次连接获得服务端同步之前不参与活动的 TP 计算。
