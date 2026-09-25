package dev.duardo.neotransfer.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitiveCouponDataTest {
    @Test fun `coupon credential labels and table columns are sensitive without PIN or token words`() {
        val values = listOf(
            "Datos del cupon: COUPONVALUE_01",
            "Banco Bandec: pago del cupon de combustible fue completado.\nDatos del cupón:\nCOUPONVALUE_02",
            "Estado del cupon.\r\nDATOS\r\nDEL\tCUPO\u0301N :\r\nCOUPONVALUE_03",
            "Mis cupones de combustible. No.Serie;Saldo actual;Importe pagado;Moneda;ID Banco;ID TM;Fecha;Banco;DatosCupon " +
                "0000000000001;10;50;CUP;B01;T01;24/09/2026;BANDEC;COUPONVALUE_04 0000000000002;20;50;CUP;B02;T02;24/09/2026;BANDEC;COUPONVALUE_05",
            "No.Serie;\n\"DatosCupón\"\n0000000000001;COUPONVALUE_06",
            "{\"DatosCupon\":\"COUPONVALUE_07\",\"No.Serie\":\"0000000000001\"}",
            "Datos_Cupon:\nCOUPONVALUE_08",
            "No.Serie;Datos del\nCupón;Saldo\n0000000000001;COUPONVALUE_09;10",
        )
        values.forEach {
            assertTrue(containsSecret(it))
            assertFalse("COUPONVALUE_" in safeArchive(it))
        }
    }

    @Test fun `similar labels and ordinary coupon information retain their content`() {
        listOf("Los datos del cupón se consultan en la aplicación.", "Datos del cupón disponibles en la aplicación.",
            "MetadatosCupon: información", "DatosCupones: dos", "No.Serie;Saldo;DatosCuponFormato",
            "DatosCuponInfo: ayuda", "Mis cupones de combustible: No.Serie: 0000000000001, Saldo actual: 10.00 CUP",
            "Banco Bandec: La consulta de saldo fue completada. Saldo Disponible: CR 100.00 CUP").forEach {
            assertFalse(containsSecret(it))
            assertEquals(it, safeArchive(it))
        }
    }

    @Test fun `structured archive removes only credential aliases from nested coupon lists`() {
        val aliases = listOf("DatosCupon", "Datos del cupón", "datos_cupon", "datos-del-cupon", "DATOS\nDEL\tCUPÓN")
        val rows = JSONArray().apply { aliases.forEachIndexed { index, alias -> put(JSONObject().apply {
            put("serie", "000000000000$index"); put("saldo", "10.00"); put("moneda", "CUP")
            put(alias, "COUPONVALUE_$index"); put("DatosCuponFormato", "documentado")
        }) } }
        val cleaned = JSONObject(safeArchive(JSONObject().put("cupones", rows))).getJSONArray("cupones")
        aliases.forEachIndexed { index, alias ->
            assertTrue(sensitiveKey(alias))
            assertFalse(cleaned.getJSONObject(index).has(alias))
            assertEquals("000000000000$index", cleaned.getJSONObject(index).getString("serie"))
            assertEquals("10.00", cleaned.getJSONObject(index).getString("saldo"))
            assertEquals("CUP", cleaned.getJSONObject(index).getString("moneda"))
            assertEquals("documentado", cleaned.getJSONObject(index).getString("DatosCuponFormato"))
        }
        assertFalse(sensitiveKey("DatosCuponFormato"))
        assertFalse("COUPONVALUE_" in safeArchive("{\"DatosCupon\":\"COUPONVALUE_BROKEN\""))
    }
}
