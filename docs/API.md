# Advanced Teleport 注册 API

ATP 提供服务端 Java API，供后续站点模组、城市模组和特殊道路模组注册交通数据。调用入口是 `advanced_teleport.api.AdvancedTeleportApi`；不可通过客户端网络包直接注册受保护记录。

## 依赖与调用时机

在接入模组的 `fabric.mod.json` 中声明 ATP 依赖：

```json
{
  "depends": {
    "minecraft": "~1.20.4",
    "fabric-api": "*",
    "advanced_teleport": ">=1.0.0"
  }
}
```

本地开发可将构建后的 ATP JAR 放入接入工程的 `libs`，由 Fabric Loom 处理命名空间映射：

```groovy
dependencies {
    modImplementation files('libs/advanced_teleport-1.0.0.jar')
}
```

需要在 ATP 完成世界初始化后调用，例如服务端命令回调或正常运行中的 tick 回调。**所有 API 调用都要求 Minecraft 服务端线程**；异步任务应先用 `server.execute(() -> { ... })` 切换线程。单机也使用集成服务端的 API，不修改客户端缓存。

## 入口

| 调用 | 行为 |
| --- | --- |
| `registerCity(server, City)` | 注册或更新城市 |
| `registerRoad(server, Road)` | 注册或更新道路 |
| `registerStation(server, Station)` | 注册或更新站点，并验证道路存在及站点在线上 |
| `registerPortal(server, PortalLink)` | 注册或更新显式配对的传送门 |
| `unregister(server, type, id)` | 显式删除记录；`type` 为 `city`、`road`、`station` 或 `portal` |
| `snapshot(server)` | 返回不可变的当前 `GraphData.Snapshot` |

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
Snapshot(long revision, List<City> cities, List<Road> roads,
         List<Station> stations, List<PortalLink> portals)
```

`RoadKind` 的取值为 `RAIL`、`ICE`、`API`。特殊模组道路通常使用 `API`；类型不改变几何连通规则。

- `dimension` 使用完整维度 ID，如 `minecraft:overworld`、`minecraft:the_nether`。坐标必须为有限数值。
- 城市至少 3 个同维度顶点，按边界顺序给出；闭合边自动连接最后和第一个顶点，不需要重复首点。城市范围使用 X/Z，多边形顶点的 Y 不构成高度边界。
- 道路至少 2 个同维度点，依次形成三维折线。整条注册道路作为一个路网对象参与连通，不要求自动检测对应方块。
- 站点以 `roadId` 指定道路，必须与该道路同维度，并在折线上或离折线不超过 0.1 格。普通站点最好放在有安全落脚空间的位置。
- 传送门连接的两个端点可以处于不同维度。它们通过服务端的 `portalConnectionDistance` 连接附近道路。
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

两端附近都需要有道路，才能将两个维度的路网连接起来。这个 API 用于接入模组已经知道端点的自定义传送设施；ATP 自己发现的原版门会在实际穿门之后建立配对。只注册连接不会自动建造方块或生成落脚平台。

## 连通与传送规则

城市与城市不因为重叠自动合并；道路穿过多边形边界也能建立城市连接，不要求折线顶点正好落在城市内。道路间按三维线段最短距离判断，避免只比较端点而漏掉交叉处。连通关系可沿道路、城市和配对门传递。

`default` 选择起点连通范围内、目标维度中最接近目标的几何位置。`strict` 要求起点靠近一个有效站点，选择起点网络中距目标网络最近的站点；它不会直接跳到一个原本不属于起点网络的目标站点。两种模式都由服务端独立复算，并在实际传送前检查安全落脚空间。

API 没有赋予普通玩家额外管理权限，也不提供客户端自声明受保护数据的通道。需要界面选点的接入模组应自行处理权限、输入和交互，再在服务端调用这里的 API。
