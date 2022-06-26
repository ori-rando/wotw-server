package wotw.server.database.model

import kotlinx.serialization.serializer
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime
import wotw.io.messages.Preset
import wotw.server.database.jsonb

object Seeds : LongIdTable("seeds") {
    val seedgenConfig = jsonb("seedgen_config", serializer<Preset>())
    val spoiler = jsonb("spoiler")
    val spoilerText = text("spoiler_text")
    val creator = optReference("creator_id", Users)
    val created = datetime("created_at").defaultExpression(CurrentDateTime)
}

class Seed(id: EntityID<Long>): LongEntity(id){
    companion object : LongEntityClass<Seed>(Seeds)

    var seedgenConfig by Seeds.seedgenConfig
    var creator by User optionalReferencedOn Seeds.creator
    val worldSeeds by WorldSeed referrersOn WorldSeeds.seed
}

object WorldSeeds : LongIdTable("world_seeds") {
    val seed = reference("seed_id", Seeds)
    val worldIndex = integer("world_index")
    val content = text("content")
}

class WorldSeed(id: EntityID<Long>): LongEntity(id){
    companion object : LongEntityClass<WorldSeed>(WorldSeeds)

    var seed by Seed referencedOn WorldSeeds.seed
    var worldIndex by WorldSeeds.worldIndex
    var content by WorldSeeds.content
}