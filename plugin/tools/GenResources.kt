import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.size == 2) {
        "Usage: GenResources <minecraft-data/26.2> <resource-directory>"
    }

    generateResources(
        Path.of(args[0]).toAbsolutePath().normalize(),
        Path.of(args[1]).toAbsolutePath().normalize(),
    )
}

private fun generateResources(sourceDirectory: Path, resourceDirectory: Path) {
    val blocksBytes = Files.readAllBytes(sourceDirectory.resolve("blocks.json"))
    val shapesBytes = Files.readAllBytes(sourceDirectory.resolve("blockCollisionShapes.json"))
    val entitiesBytes = Files.readAllBytes(sourceDirectory.resolve("entities.json"))
    val itemsBytes = Files.readAllBytes(sourceDirectory.resolve("items.json"))
    val blocks = parse(blocksBytes).asJsonArray
    val collisionData = parse(shapesBytes).asJsonObject
    val entityData = parse(entitiesBytes).asJsonArray
    val itemData = parse(itemsBytes).asJsonArray
    val collisionShapes = collisionData.getAsJsonObject("shapes")
    val collisionBlocks = collisionData.getAsJsonObject("blocks")
    val shapeIds = collisionShapes.keySet().map(String::toInt).sorted()

    shapeIds.forEachIndexed { index, shapeId ->
        check(shapeId == index) {
            "Collision shape ids are not dense at $index: $shapeId"
        }
    }

    val stateCount = blocks.maxOf { it.asJsonObject.get("maxStateId").asInt } + 1
    val states = arrayOfNulls<BlockState>(stateCount)
    for (blockElement in blocks) {
        val block = blockElement.asJsonObject
        val blockName = block.get("name").asString
        val minStateId = block.get("minStateId").asInt
        val maxStateId = block.get("maxStateId").asInt
        val count = maxStateId - minStateId + 1
        val physicsStates = block.get("physicsStates")?.arrayOrNull()
        if (physicsStates == null || physicsStates.size() != count) {
            error("Block $blockName has $count states but ${physicsStates?.size()} physics states")
        }

        val blockShape = collisionBlocks.get(blockName)
            ?: error("Block $blockName has no collision shape mapping")
        if (blockShape.isJsonArray && blockShape.asJsonArray.size() != count) {
            error("Block $blockName has $count states but ${blockShape.asJsonArray.size()} shape mappings")
        }

        for (offset in 0 until count) {
            val stateId = minStateId + offset
            check(states[stateId] == null) { "Duplicate block state id $stateId" }
            val shapeIdElement = if (blockShape.isJsonArray) blockShape.asJsonArray[offset] else blockShape
            val shapeId = shapeIdElement.integerOrNull()
            if (shapeId == null || !collisionShapes.has(shapeId.toString())) {
                error("Block state $stateId references missing collision shape $shapeId")
            }
            val physics = physicsStates[offset].asJsonObject
            val shapeBoxes = collisionShapes.getAsJsonArray(shapeId.toString())
            val fluidFaceMask = physics.get("fluidFaceMask").integerOrNull()
                ?: deriveFluidFaceMask(blockName, shapeBoxes)
            val friction = block.get("friction").finiteDoubleOrNull()
            val speedFactor = block.get("speedFactor").finiteDoubleOrNull()
            val bounciness = block.get("bounciness").finiteDoubleOrNull()
            val stateKey = physics.get("stateKey").stringOrNull()
            val fluidAmount = physics.get("fluidAmount").integerOrNull()
            val scaffoldingDistance = physics.get("scaffoldingDistance").integerOrNull()
            val scaffoldingBottom = physics.get("scaffoldingBottom").booleanOrNull()
            if (friction == null || speedFactor == null || bounciness == null
                || stateKey == null || fluidAmount == null || fluidAmount !in 0..9
                || scaffoldingDistance == null || scaffoldingDistance !in 0..7
                || scaffoldingBottom == null || fluidFaceMask !in 0..0x3f
            ) {
                error("Block state $stateId has invalid physics values")
            }
            val behaviorKind = blockBehavior(blockName, physics.get("climbable").booleanOrNull() == true)
            states[stateId] = BlockState(
                shapeId = shapeId,
                stateKey = stateKey,
                blockId = block.get("id").asInt,
                friction = friction,
                speedFactor = speedFactor,
                bounciness = bounciness,
                flags = (if (physics.get("air").booleanOrNull() == true) 1 else 0)
                        or (if (physics.get("water").booleanOrNull() == true) 2 else 0)
                        or (if (physics.get("fluidFalling").booleanOrNull() == true) 4 else 0)
                        or (if (physics.get("lava").booleanOrNull() == true) 8 else 0),
                fluidAmount = fluidAmount,
                behaviorKind = behaviorKind,
                behaviorParameter = if (behaviorKind == 10) {
                    scaffoldingDistance shl 1 or if (scaffoldingBottom) 1 else 0
                } else if (physics.get("bubbleDragDown").booleanOrNull() == true) {
                    1
                } else {
                    0
                },
                fluidFaceMask = fluidFaceMask,
            )
        }
    }

    val missingState = states.indexOfFirst { it == null }
    check(missingState == -1) { "Missing block state id $missingState" }

    val entityCount = entityData.maxOf { it.asJsonObject.get("id").asInt } + 1
    val entities = arrayOfNulls<EntityDefinition>(entityCount)
    for (entityElement in entityData) {
        val entity = entityElement.asJsonObject
        val entityName = entity.get("name").asString
        val id = entity.get("id").integerOrNull()
        val posesJson = entity.get("poseDimensions")?.arrayOrNull()
        val metadataDefaults = entity.get("metadataDefaults")?.objectOrNull()
        val metadataSchema = entity.get("metadataSchema")?.objectOrNull()
        if (id == null || id !in entities.indices || entities[id] != null
            || posesJson == null || metadataDefaults == null || metadataSchema == null
        ) {
            error("Entity $entityName has invalid fixed physics data")
        }
        val living = entity.get("living").booleanOrNull() == true
        val movementCollision = entity.get("movementCollision").booleanOrNull()
            ?: defaultMovementCollision(entityName)
        val pistonReaction = entity.get("pistonReaction").integerOrNull()
            ?: defaultPistonReaction(entityName)
        val attributes = entity.get("movementAttributes")?.objectOrNull()
        if (living && (attributes == null || attributes.entrySet().any { it.value.finiteDoubleOrNull() == null })) {
            error("Living entity $entityName has incomplete movement attribute defaults")
        }
        val poses = posesJson.mapIndexed { poseIndex, poseElement ->
            val pose = poseElement.asJsonObject
            val passengerAttachments = pose.get("passengerAttachments")?.arrayOrNull()
            val vehicleAttachment = pose.get("vehicleAttachment")?.arrayOrNull()
            if (pose.get("pose").integerOrNull() != poseIndex
                || pose.get("width").finiteDoubleOrNull() == null
                || pose.get("height").finiteDoubleOrNull() == null
                || pose.get("eyeHeight").finiteDoubleOrNull() == null
                || passengerAttachments == null
                || passengerAttachments.any { !it.isVector() }
                || vehicleAttachment == null || !vehicleAttachment.isVector()
            ) {
                error("Entity $entityName has invalid pose $poseIndex")
            }
            EntityPose(
                pose = poseIndex,
                width = pose.get("width").asDouble,
                height = pose.get("height").asDouble,
                eyeHeight = pose.get("eyeHeight").asDouble,
                passengerAttachments = passengerAttachments.map(JsonElement::vector),
                vehicleAttachment = vehicleAttachment.vector(),
            )
        }
        val sharedFlags = metadataDefaults.get("sharedFlags").integerOrNull()
        val defaultPose = metadataDefaults.get("pose").integerOrNull()
        val noGravity = metadataDefaults.get("noGravity").booleanOrNull()
        val defaultHealth = metadataDefaults.get("health").finiteDoubleOrNull()
        if (poses.isEmpty() || sharedFlags == null || defaultPose == null || defaultPose !in poses.indices
            || noGravity == null || defaultHealth == null || pistonReaction !in 0..1
        ) {
            error("Entity $entityName has invalid metadata defaults")
        }
        val metadataIds = listOf(
            "sharedFlags", "noGravity", "pose", "health", "horseFlags", "steeringBoost",
            "striderSuffocating", "camelLastPoseChangeTick", "happyGhastStaysStill",
        ).map { metadataSchema.get(it).integerOrNull() }
        if (metadataIds.any { it == null || it !in -1..254 }
            || metadataIds[0]!! < 0 || metadataIds[1]!! < 0 || metadataIds[2]!! < 0
            || living && metadataIds[3]!! < 0
        ) {
            error("Entity $entityName has invalid metadata schema")
        }
        entities[id] = EntityDefinition(
            id = id,
            poses = poses,
            movementKind = movementKind(entityName),
            flags = (if (living) 1 else 0)
                    or (if (entity.get("pushable").booleanOrNull() == true) 2 else 0)
                    or (if (noGravity) 4 else 0)
                    or (if (movementCollision) 8 else 0),
            pistonReaction = pistonReaction,
            sharedFlags = sharedFlags,
            defaultPose = defaultPose,
            defaultHealth = defaultHealth,
            metadataIds = metadataIds.map { it!! },
            gravity = if (living) attributes!!.get("gravity").asDouble else 0.0,
            scale = if (living) attributes!!.get("scale").asDouble else 1.0,
            stepHeight = if (living) attributes!!.get("stepHeight").asDouble else 0.0,
            movementSpeed = if (living) attributes!!.get("movementSpeed").asDouble else 0.0,
            movementEfficiency = if (living) attributes!!.get("movementEfficiency").asDouble else 0.0,
            waterMovementEfficiency = if (living) attributes!!.get("waterMovementEfficiency").asDouble else 0.0,
            bounciness = if (living) attributes!!.get("bounciness").asDouble else 0.0,
        )
    }
    val missingEntity = entities.indexOfFirst { it == null }
    check(missingEntity == -1) { "Missing entity protocol id $missingEntity" }

    fun itemId(name: String): Int {
        val item = itemData.firstOrNull { it.asJsonObject.get("name").asString == name }?.asJsonObject
        val id = item?.get("id").integerOrNull()
        return id ?: error("Missing fixed item id $name")
    }

    val leatherBootsItemId = itemId("leather_boots")
    val elytraItemId = itemId("elytra")
    val saddleItemId = itemId("saddle")
    val carrotOnAStickItemId = itemId("carrot_on_a_stick")
    val warpedFungusOnAStickItemId = itemId("warped_fungus_on_a_stick")
    val harnessItemIds = itemData
        .filter { it.asJsonObject.get("name").asString.endsWith("_harness") }
        .map { it.asJsonObject.get("id").integerOrNull() }
    if (harnessItemIds.isEmpty() || harnessItemIds.any { it == null }) {
        error("Missing fixed harness item ids")
    }

    val resource = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(states.size)
            output.writeInt(shapeIds.size)
            output.writeInt(entities.size)
            output.writeInt(leatherBootsItemId)
            output.writeInt(elytraItemId)
            output.writeInt(saddleItemId)
            output.writeInt(carrotOnAStickItemId)
            output.writeInt(warpedFungusOnAStickItemId)
            output.writeShort(harnessItemIds.size)
            harnessItemIds.forEach { output.writeInt(it!!) }

            for (shapeId in shapeIds) {
                val boxes = collisionShapes.get(shapeId.toString()).arrayOrNull()
                if (boxes == null || boxes.size() > 0xffff) {
                    error("Collision shape $shapeId has invalid boxes")
                }
                output.writeShort(boxes.size())
                for (boxElement in boxes) {
                    val box = boxElement.arrayOrNull()
                    if (box == null || box.size() != 6 || box.any { it.finiteDoubleOrNull() == null }) {
                        error("Collision shape $shapeId has an invalid AABB")
                    }
                    box.forEach { output.writeFloat(it.asDouble.toFloat()) }
                }
            }

            for (state in states) {
                state!!
                output.writeText(state.stateKey)
                output.writeInt(state.shapeId)
                output.writeInt(state.blockId)
                output.writeFloat(state.friction.toFloat())
                output.writeFloat(state.speedFactor.toFloat())
                output.writeFloat(state.bounciness.toFloat())
                output.write(state.flags)
                output.write(state.fluidAmount)
                output.write(state.behaviorKind)
                output.write(state.behaviorParameter)
                output.write(state.fluidFaceMask)
            }

            for (entity in entities) {
                entity!!
                output.writeInt(entity.id)
                output.write(entity.movementKind)
                output.write(entity.flags)
                output.write(entity.pistonReaction)
                output.write(entity.sharedFlags)
                output.write(entity.defaultPose)
                entity.metadataIds.forEach(output::writeInt)
                output.writeFloat(entity.defaultHealth.toFloat())
                output.write(entity.poses.size)
                for ((pose1, width, height, eyeHeight, passengerAttachments, vehicleAttachment) in entity.poses) {
                    if (passengerAttachments.size > 0xff) {
                        error("Entity ${entity.id} pose $pose1 has too many passenger attachments")
                    }
                    output.writeFloat(width.toFloat())
                    output.writeFloat(height.toFloat())
                    output.writeFloat(eyeHeight.toFloat())
                    output.write(passengerAttachments.size)
                    passengerAttachments.forEach { attachment ->
                        attachment.forEach(output::writeFloat)
                    }
                    vehicleAttachment.forEach(output::writeFloat)
                }
                output.writeDouble(entity.gravity)
                output.writeDouble(entity.scale)
                output.writeDouble(entity.stepHeight)
                output.writeDouble(entity.movementSpeed)
                output.writeDouble(entity.movementEfficiency)
                output.writeDouble(entity.waterMovementEfficiency)
                output.writeDouble(entity.bounciness)
            }
        }
        bytes.toByteArray()
    }

    Files.createDirectories(resourceDirectory)
    Files.write(resourceDirectory.resolve("minecraft-data.bin"), resource)
}

private fun parse(bytes: ByteArray): JsonElement =
    JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8))

private fun blockBehavior(name: String, climbable: Boolean): Int = when {
    name == "bubble_column" -> 1
    name == "cobweb" -> 2
    name == "sweet_berry_bush" -> 3
    name == "powder_snow" -> 4
    name == "honey_block" -> 5
    name == "slime_block" -> 6
    name.endsWith("_bed") -> 7
    name == "soul_sand" -> 8
    name == "scaffolding" -> 10
    climbable -> 9
    else -> 0
}

private fun movementKind(name: String): Int = when {
    name.endsWith("_boat") || name.endsWith("_raft") -> 2
    name.contains("minecart") -> 1
    name in setOf("horse", "donkey", "mule", "skeleton_horse", "zombie_horse") -> 3
    name in setOf("camel", "camel_husk") -> 4
    name == "pig" -> 5
    name == "strider" -> 6
    name == "happy_ghast" -> 7
    name in setOf("nautilus", "zombie_nautilus") -> 8
    else -> 0
}

private fun defaultMovementCollision(name: String): Boolean =
    name.endsWith("_boat") || name.endsWith("_raft") || name == "shulker"

private fun defaultPistonReaction(name: String): Int =
    if (name in setOf(
            "area_effect_cloud", "block_display", "interaction", "item_display",
            "marker", "ominous_item_spawner", "text_display",
        )
    ) {
        1
    } else {
        0
    }

private fun deriveFluidFaceMask(blockName: String, boxes: JsonArray): Int {
    if (blockName == "ice" || blockName == "frosted_ice") return 0
    var mask = 0
    for (direction in 0 until 6) {
        if (supportsFullFace(boxes, direction)) mask = mask or (1 shl direction)
    }
    return mask
}

private fun supportsFullFace(boxes: JsonArray, direction: Int): Boolean {
    val faceBoxes = boxes.map(JsonElement::vectorDouble).filter { switchFace(direction, it) }
    if (faceBoxes.isEmpty()) return false
    val rectangles = faceBoxes.map { box ->
        when {
            direction <= 1 -> doubleArrayOf(box[0], box[2], box[3], box[5])
            direction <= 3 -> doubleArrayOf(box[0], box[1], box[3], box[4])
            else -> doubleArrayOf(box[2], box[1], box[5], box[4])
        }
    }
    val xs = (listOf(0.0, 1.0) + rectangles.flatMap { listOf(it[0], it[2]) }).distinct().sorted()
    val ys = (listOf(0.0, 1.0) + rectangles.flatMap { listOf(it[1], it[3]) }).distinct().sorted()
    for (x in 0 until xs.size - 1) {
        for (y in 0 until ys.size - 1) {
            val middleX = (xs[x] + xs[x + 1]) / 2
            val middleY = (ys[y] + ys[y + 1]) / 2
            if (middleX in 0.0..1.0 && middleY in 0.0..1.0
                && rectangles.none {
                    middleX >= it[0] && middleX <= it[2]
                        && middleY >= it[1] && middleY <= it[3]
                }
            ) {
                return false
            }
        }
    }
    return true
}

private fun switchFace(direction: Int, box: DoubleArray): Boolean = when (direction) {
    0 -> box[1] <= 0
    1 -> box[4] >= 1
    2 -> box[2] <= 0
    3 -> box[5] >= 1
    4 -> box[0] <= 0
    else -> box[3] >= 1
}

private fun DataOutputStream.writeText(value: String) {
    val bytes = value.toByteArray(StandardCharsets.US_ASCII)
    check(bytes.size <= 0xffff) { "Header text is too long: $value" }
    writeShort(bytes.size)
    write(bytes)
}

private fun JsonElement?.arrayOrNull(): JsonArray? =
    if (this != null && isJsonArray) asJsonArray else null

private fun JsonElement?.objectOrNull(): JsonObject? =
    if (this != null && isJsonObject) asJsonObject else null

private fun JsonElement?.stringOrNull(): String? =
    if (this != null && isJsonPrimitive && asJsonPrimitive.isString) asString else null

private fun JsonElement?.booleanOrNull(): Boolean? =
    if (this != null && isJsonPrimitive && asJsonPrimitive.isBoolean) asBoolean else null

private fun JsonElement?.finiteDoubleOrNull(): Double? {
    if (this == null || !isJsonPrimitive || !asJsonPrimitive.isNumber) return null
    return asDouble.takeIf(Double::isFinite)
}

private fun JsonElement?.integerOrNull(): Int? {
    val value = finiteDoubleOrNull() ?: return null
    if (value % 1.0 != 0.0 || value < Int.MIN_VALUE || value > Int.MAX_VALUE) return null
    return value.toInt()
}

private fun JsonElement.isVector(): Boolean =
    arrayOrNull()?.let { it.size() == 3 && it.all { value -> value.finiteDoubleOrNull() != null } } == true

private fun JsonElement.vector(): FloatArray =
    asJsonArray.map { it.asDouble.toFloat() }.toFloatArray()

private fun JsonElement.vectorDouble(): DoubleArray =
    asJsonArray.map(JsonElement::getAsDouble).toDoubleArray()

private data class BlockState(
    val shapeId: Int,
    val stateKey: String,
    val blockId: Int,
    val friction: Double,
    val speedFactor: Double,
    val bounciness: Double,
    val flags: Int,
    val fluidAmount: Int,
    val behaviorKind: Int,
    val behaviorParameter: Int,
    val fluidFaceMask: Int,
)

private data class EntityPose(
    val pose: Int,
    val width: Double,
    val height: Double,
    val eyeHeight: Double,
    val passengerAttachments: List<FloatArray>,
    val vehicleAttachment: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EntityPose

        if (pose != other.pose) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (eyeHeight != other.eyeHeight) return false
        if (passengerAttachments != other.passengerAttachments) return false
        if (!vehicleAttachment.contentEquals(other.vehicleAttachment)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pose
        result = 31 * result + width.hashCode()
        result = 31 * result + height.hashCode()
        result = 31 * result + eyeHeight.hashCode()
        result = 31 * result + passengerAttachments.hashCode()
        result = 31 * result + vehicleAttachment.contentHashCode()
        return result
    }
}

private data class EntityDefinition(
    val id: Int,
    val poses: List<EntityPose>,
    val movementKind: Int,
    val flags: Int,
    val pistonReaction: Int,
    val sharedFlags: Int,
    val defaultPose: Int,
    val defaultHealth: Double,
    val metadataIds: List<Int>,
    val gravity: Double,
    val scale: Double,
    val stepHeight: Double,
    val movementSpeed: Double,
    val movementEfficiency: Double,
    val waterMovementEfficiency: Double,
    val bounciness: Double,
)
