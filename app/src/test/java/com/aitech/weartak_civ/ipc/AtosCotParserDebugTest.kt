package com.aitech.weartak_civ.ipc

import org.junit.Test

class AtosCotParserDebugTest {
    @Test
    fun printParseReason() {
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
                    <track course="22" speed="0.025" vspeed="1.0" />
                    <__atos tag_type="Vehicle" color="Maroon" tag_uid="4"
                        commandlink="CL-12345" temp_c="5" voltage="3.6" alarm="0">
                        <attributes Remark="remark" Frequency="frq"
                            Special_Equipment="equp" Vehicle_Type="tacoma"
                            Driver_Callsign="hodor" Team_Frequency="420.69" />
                    </__atos>
                    <link relation="p-p" uid="android-device" type="a-f-g"/>
                    <archive />
                </detail>
            </event>
        """.trimIndent()

        val result = AtosCotParser.parse(xml)
        println("DEBUG_PARSE_RESULT=$result")
    }
}
