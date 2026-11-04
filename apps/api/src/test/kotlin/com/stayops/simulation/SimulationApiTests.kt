package com.stayops.simulation

import com.stayops.TestcontainersConfiguration
import com.stayops.simulation.engine.SimDataset
import com.stayops.simulation.engine.SimPolicy
import com.stayops.simulation.engine.SimulationEngine
import com.stayops.support.ApiClient
import com.stayops.support.ApiClient.Companion.assertProblem
import com.stayops.support.StayWorld
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * P11 시뮬레이션 API (docs/v4.1/02 §9). 데이터셋은 Python 생성기가 만든 계약 fixture
 * (`resources/simulation/dataset-contract.json`, pytest가 생성기 출력과 같은지 확인)를 그대로 올린다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class SimulationApiTests(
    @Autowired private val json: JsonMapper,
    @Autowired private val world: StayWorld,
    @LocalServerPort private val port: Int,
) {
    private lateinit var api: ApiClient
    private lateinit var hotel: StayWorld.Hotel

    @BeforeEach
    fun setUp() {
        api = ApiClient(port, json)
        hotel = world.newHotel()
    }

    private val fixtureText: String = javaClass.getResource("/simulation/dataset-contract.json")!!.readText()
    private fun fixture(): ObjectNode = json.readTree(fixtureText) as ObjectNode
    private val base get() = "/api/v1/properties/${hotel.property.getId()}"

    private fun importDataset(body: JsonNode = fixture(), by: java.util.UUID = hotel.manager.getId()) =
        api.post("$base/simulation-datasets", json.treeToValue(body, Map::class.java), by)

    private fun runSimulation(datasetId: String, policies: List<String>? = null, key: String = java.util.UUID.randomUUID().toString()) =
        api.post("$base/simulations", mapOf("datasetId" to datasetId, "policies" to policies), hotel.manager.getId(), key)

    @Test
    fun `importing the same dataset twice returns the same immutable snapshot`() {
        val first = importDataset()
        val second = importDataset()

        assertEquals(201, first.status, "body=${first.body}")
        assertEquals(true, first.body.get("created").asBoolean())
        assertEquals(6, first.body.get("summary").get("rooms").asInt())
        assertEquals(7, first.body.get("summary").get("seed").asInt())
        assertEquals(true, first.body.get("synthetic").asBoolean())
        assertEquals(200, second.status)
        assertEquals(false, second.body.get("created").asBoolean())
        assertEquals(first.text("datasetId"), second.text("datasetId"))
    }

    @Test
    fun `non-synthetic, unsupported or malformed datasets are rejected`() {
        val notSynthetic = fixture().also { (it.get("metadata") as ObjectNode).put("synthetic", false) }
        val response = importDataset(notSynthetic)
        assertProblem(response, 400, "VALIDATION_ERROR")
        assertTrue(response.body.get("problems").toString().contains("synthetic"))

        val wrongVersion = fixture().also { (it.get("metadata") as ObjectNode).put("generatorVersion", "gen-v9") }
        assertProblem(importDataset(wrongVersion), 400, "VALIDATION_ERROR")

        val missing = fixture().also { it.remove("reservations") }
        assertProblem(importDataset(missing), 400, "VALIDATION_ERROR")
    }

    @Test
    fun `comparison runs every policy on the same snapshot and reports differences from the baseline`() {
        val datasetId = importDataset().text("datasetId")!!
        val response = runSimulation(datasetId)

        assertEquals(201, response.status, "body=${response.body}")
        val run = response.body
        assertEquals("COMPLETED", response.text("status"))
        assertEquals(SimulationEngine.ENGINE_VERSION, response.text("engineVersion"))
        assertEquals(SimulationEngine.POLICY_VERSION, response.text("policyVersion"))
        assertEquals(true, run.get("synthetic").asBoolean())
        assertTrue(run.get("definitions").has("onTimeReadinessRate"))
        assertTrue(run.get("assumptions").size() > 0)

        val scenarios = (0 until run.get("scenarios").size()).map { run.get("scenarios").get(it) }
        assertEquals(listOf("BASELINE_FIFO", "DEADLINE_FIRST", "SAME_SHIFT_START"), scenarios.map { it.get("policy").asString() })
        assertTrue(scenarios[0].get("diffFromBaseline").isNull)
        assertEquals("DEADLINE_FIRST", scenarios[1].get("diffFromBaseline").get("parameters").get("dispatchRule").get("scenario").asString())
        assertEquals("ALL_09:00", scenarios[2].get("diffFromBaseline").get("parameters").get("housekeeperShiftStart").get("scenario").asString())
        assertTrue(scenarios[1].get("diffFromBaseline").get("metrics").has("onTimeReadinessRate"))

        // API 결과 = 같은 데이터셋으로 엔진을 직접 실행한 결과
        val dataset = json.readValue(fixtureText, SimDataset::class.java)
        val direct = SimulationEngine.run(dataset, SimPolicy.DEADLINE_FIRST)
        assertEquals(direct, json.treeToValue(scenarios[1].get("metrics"), com.stayops.simulation.engine.SimMetrics::class.java))
    }

    @Test
    fun `same dataset and policies reproduce identical results, and a replayed request returns the same run`() {
        val datasetId = importDataset().text("datasetId")!!
        val a = runSimulation(datasetId, key = "sim-1")
        val replay = runSimulation(datasetId, key = "sim-1")
        val b = runSimulation(datasetId, key = "sim-2")

        assertEquals(a.text("runId"), replay.text("runId"))
        assertNotEquals(a.text("runId"), b.text("runId"))
        assertEquals(a.body.get("scenarios"), b.body.get("scenarios"))
    }

    @Test
    fun `policies must start with the baseline and the dataset must belong to the property`() {
        val datasetId = importDataset().text("datasetId")!!
        assertProblem(runSimulation(datasetId, listOf("DEADLINE_FIRST")), 400, "VALIDATION_ERROR")
        val subset = runSimulation(datasetId, listOf("BASELINE_FIFO", "SAME_SHIFT_START"))
        assertEquals(2, subset.body.get("scenarios").size())

        val other = world.newHotel()
        val foreign = api.post("/api/v1/properties/${other.property.getId()}/simulations", mapOf("datasetId" to datasetId), other.manager.getId())
        assertProblem(foreign, 404, "NOT_FOUND")
    }

    @Test
    fun `managers run, front desk reads, housekeepers cannot see simulations`() {
        val datasetId = importDataset().text("datasetId")!!
        val runId = runSimulation(datasetId).text("runId")!!

        assertProblem(importDataset(by = hotel.frontDesk.getId()), 403, "FORBIDDEN")
        assertProblem(api.post("$base/simulations", mapOf("datasetId" to datasetId), hotel.frontDesk.getId()), 403, "FORBIDDEN")
        assertEquals(200, api.get("$base/simulations/$runId", hotel.frontDesk.getId()).status)
        val list = api.get("$base/simulations", hotel.frontDesk.getId())
        assertEquals(runId, list.body.get(0).get("runId").asString())
        assertEquals(1, api.get("$base/simulation-datasets", hotel.frontDesk.getId()).body.size())
        assertProblem(api.get("$base/simulations/$runId", hotel.housekeeper.getId()), 403, "FORBIDDEN")
    }
}
