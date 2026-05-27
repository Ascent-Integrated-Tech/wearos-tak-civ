package com.aitech.weartak_civ.ipc

import org.junit.Assert.assertTrue
import org.junit.Test

class AtosCotSerializerTest {
    @Test
    fun serializerPreservesAtosTagFields() {
        val xml = """
            <event version="2.0" uid="atos-plugin-4" type="a-f-G-U-C-I-I"
                time="2021-10-25T15:52:40.61Z"
                start="2021-10-25T15:52:40.61Z"
                stale="2021-10-25T15:57:40.61Z"
                how="m-g">
                <point lat="35.7985556" lon="-78.6577434"
                    hae="110.750658846955"
                    ce="9999999" le="9999999" />
                <detail>
                    <contact callsign="hodor" />
                    <track course="22" speed="0.025" vspeed="1.0" slope="0.5" />
                    <__atos tag_type="Vehicle" color="Maroon" tag_uid="4"
                        commandlink="CL-12345" temp_c="5" voltage="3.6" alarm="0" manifest="Default Manifest">
                        <attributes Remark="remark" Frequency="frq"
                            Special_Equipment="equp" Vehicle_Type="tacoma"
                            Driver_Callsign="hodor" Team_Frequency="420.69" />
                    </__atos>
                    <link relation="p-p" uid="android-device" type="a-f-g" parent_callsign="PAX 1"/>
                    <archive />
                </detail>
            </event>
        """.trimIndent()

        val packet = (AtosCotParser.parse(xml) as AtosCotParseResult.Success).packet
        val rebuilt = AtosCotSerializer.toCotXml(packet)

        assertTrue(rebuilt.contains("uid=\"atos-plugin-4\""))
        assertTrue(rebuilt.contains("type=\"a-f-G-U-C-I-I\""))
        assertTrue(rebuilt.contains("lat=\"35.7985556\""))
        assertTrue(rebuilt.contains("slope=\"0.5\""))
        assertTrue(rebuilt.contains("manifest=\"Default Manifest\""))
        assertTrue(rebuilt.contains("parent_callsign=\"PAX 1\""))
        assertTrue(rebuilt.contains("<archive/>"))
    }
}
