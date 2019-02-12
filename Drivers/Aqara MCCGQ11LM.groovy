/**
 *  Xiaomi "Original" & Aqara Door/Window Sensor
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.7.1
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Based on SmartThings device handler code by a4refillpad
 *  With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, rinkek, ronvandegraaf, snalee, tmleafs, twonk, & veeceeoh
 *  Code reworked for use with Hubitat Elevation hub by veeceeoh
 *
 *  Known issues:
 *  + Xiaomi devices send reports based on changes, and a status report every 50-60 minutes. These settings cannot be adjusted.
 *  + The battery level / voltage is not reported at pairing. Wait for the first status report, 50-60 minutes after pairing.
 *    However, the Aqara Door/Window sensor battery level can be retrieved immediately with a short-press of the reset button.
 *  + Pairing Xiaomi devices can be difficult as they were not designed to use with a Hubitat hub.
 *    Holding the sensor's reset button until the LED blinks will start pairing mode.
 *    3 quick flashes indicates success, while one long flash means pairing has not started yet.
 *    In either case, keep the sensor "awake" by short-pressing the reset button repeatedly, until recognized by Hubitat.
 *  + The connection can be dropped without warning. To reconnect, put Hubitat in "Discover Devices" mode, and follow
 *    the same steps for pairing. As long as it has not been removed from the Hubitat's device list, when the LED
 *    flashes 3 times, the Aqara Motion Sensor should be reconnected and will resume reporting as normal
 *
 */

metadata {
	definition (name: "Xiaomi Door/Window Sensor", namespace: "veeceeoh", author: "veeceeoh") {
		capability "Contact Sensor"
		capability "Sensor"
		capability "Temperature Measurement"
		capability "Battery"

		attribute "lastCheckin", "String"
		attribute "lastOpened", "String"
		attribute "lastClosed", "String"
		attribute "batteryLastReplaced", "String"

		// fingerprint for Xiaomi "Original" Door/Window Sensor
		fingerprint endpointId: "01", profileId: "0104", deviceId: "0104", inClusters: "0000,0003,FFFF,0019", outClusters: "0000,0004,0003,0006,0008,0005,0019", manufacturer: "LUMI", model: "lumi.sensor_magnet"

		// fingerprint for Xiaomi Aqara Door/Window Sensor
		fingerprint endpointId: "01", profileId: "0104", deviceId: "5F01", inClusters: "0000,0003,FFFF,0006", outClusters: "0000,0004,FFFF", manufacturer: "LUMI", model: "lumi.sensor_magnet.aq2"

		command "resetBatteryReplacedDate"
		command "resetToClosed"
		command "resetToOpen"
	}

	preferences {
		//Battery Voltage Range
 		input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.7). Default = 2.5 Volts", description: "", type: "decimal", range: "2..2.7"
 		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.8 to 3.4). Default = 3.0 Volts", description: "", type: "decimal", range: "2.8..3.4"
 		//Logging Message Config
		input name: "infoLogging", type: "bool", title: "Enable info message logging", description: ""
		input name: "debugLogging", type: "bool", title: "Enable debug message logging", description: ""
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	def cluster = description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim()
	def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
	def valueHex = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
	def events = []

	// lastCheckin can be used with webCoRE
	sendEvent(name: "lastCheckin", value: now())

	displayDebugLog("Parsing message: ${description}")

	// Send message data to appropriate parsing function based on the type of report
	if (cluster == "0006") {
		// Parse open / closed status report
		events += parseContact(Integer.parseInt(valueHex))
	} else if (attrId == "0005") {
		displayDebugLog("Reset button was short-pressed")
		// Parse battery level from longer type of announcement message
		events += (valueHex.size() > 60) ? parseBattery(valueHex.split('FF42')[1]) : [:]
	} else if (cluster == "0000") {
	    def descMap = zigbee.parseDescriptionAsMap(description)
        def prefixLen = 4 + 2 + 4  // dni + endpoint + cluster
        def msgLen = Integer.parseInt(descMap.raw[prefixLen..(prefixLen + 1)], 16)
        events += parseXiaomiReport(descMap.raw[(prefixLen + 2)..(descMap.raw.length() - 1)])
    } else {
		displayDebugLog("Unable to parse message")
	}

	if (events != []) {
//		displayInfoLog(map.descriptionText)
		displayDebugLog("Creating event $events")
		return events
	} else
		return [:]
}

// Parse open/close report
private parseContact(closedOpen) {
	def value = ["closed", "open"]
	def desc = ["closed", "opened"]
	def coreEvent = ["lastClosed", "lastOpened"]
	
	def isChanged = (device.contact == value[closedOpen]);
	
	if (isChanged) {
		displayDebugLog("Setting ${coreEvent[closedOpen]} to current date/time for webCoRE")
		sendEvent(name: coreEvent[closedOpen], value: now(), descriptionText: "Updated ${coreEvent[closedOpen]} (webCoRE)")
	}
	return [
		name: 'contact',
		value: value[closedOpen],
		isStateChange: isChanged,
		descriptionText: "Contact was ${desc[closedOpen]}"
	]
}

// Convert raw 4 digit integer voltage value into percentage based on minVolts/maxVolts range
private parseBattery(description) {
	displayDebugLog("Battery parse string = ${description}")
	def MsgLength = description.size()
	def rawValue
	for (int i = 4; i < (MsgLength-3); i+=2) {
		if (description[i..(i+1)] == "21") { // Search for byte preceeding battery voltage bytes
			rawValue = Integer.parseInt((description[(i+4)..(i+5)] + description[(i+2)..(i+3)]),16)
			break
		}
	}
	def rawVolts = rawValue / 1000
	def minVolts = voltsmin ? voltsmin : 2.5
	def maxVolts = voltsmax ? voltsmax : 3.0
	def pct = (rawVolts - minVolts) / (maxVolts - minVolts)
	def roundedPct = Math.min(100, Math.round(pct * 100))
	def descText = "Battery level is ${roundedPct}% (${rawVolts} Volts)"
	displayInfoLog(descText)
	def result = [
		name: 'battery',
		value: roundedPct,
		unit: "%",
		isStateChange: true,
		descriptionText: descText
	]
	return result
}

private int toBigEndian(String hex) {
    int ret = 0;
    String hexBigEndian = "";
    if (hex.length() % 2 != 0) return ret;
    for (int i = hex.length() - 2; i >= 0; i -= 2) {
        hexBigEndian += hex.substring(i, i + 2);
    }
    ret = Integer.parseInt(hexBigEndian, 16);
    return ret;
}

private parseXiaomiReport(description) {
	displayDebugLog("Xiaomi parse string = ${description}")
	def msgPos = 0
	def msgLength = description.size()

    def modelId = null;
    def manufacturerSpecificValues = null;
    
    while (msgPos < msgLength) {
    	def attrId = toBigEndian(description[msgPos++..(msgPos+=3)-1])
        def dataType = Integer.parseInt(description[msgPos++..msgPos++], 16)
        def dataLen = DataType.getLength(dataType)
        
        if (dataLen == null || dataLen == -1) { // Probably variable length
            switch (dataType) {
                case DataType.STRING_OCTET:
                case DataType.STRING_CHAR:
			        dataLen = Integer.parseInt(description[msgPos++..msgPos++], 16)
                	break;
                case DataType.STRING_LONG_OCTET:
                case DataType.STRING_LONG_CHAR:
			        dataLen = toBigEndian(description[msgPos++..(msgPos+=3)-1])
                	break;
	            default:
    	            log.error("Unsupported data type in Xiaomi Report msg: attrID = 0x${Integer.toHexString(attrId)}, type = 0x${Integer.toHexString(dataType)}")
        	        return
            }
        }
        
        if (dataLen * 2 > msgLength - msgPos) {  // Yes, it happens with lumi.sensor_86sw2 (WXKG02LM)
            log.error("WTF Xiaomi, packet length received from you (${dataLen} bytes) is greater than the length of remaining data (${(msgLength - msgPos) / 2} bytes)!")
        	dataLen = (msgLength - msgPos) / 2
        }

        def dataPayload

        if (dataLen != 0)
        	dataPayload = description[msgPos++..(msgPos+=(dataLen * 2) - 1)-1]

        switch (attrId) {
            case 0xFF01:
             	manufacturerSpecificValues = parseXiaomiReport_FF01(dataPayload)
//            	log.warn(manufacturerSpecificValues);
               	break;
            case 0x0005:
             	modelId = parseXiaomiReport_0005(dataPayload)
               	break;
            default:
                log.warn("Unsupported attribute in Xiaomi Report msg: attrID = 0x${Integer.toHexString(attrId)}, type = 0x${Integer.toHexString(dataType)}, length = ${dataLen} bytes, payload = ${dataPayload}")
        }
    }

    def events = []
    
    if (manufacturerSpecificValues.containsKey("BatteryPct")) {
        events += [
			name: 'battery',
			value: manufacturerSpecificValues["BatteryPct"],
			unit: "%",
			isStateChange: true,
			descriptionText: "Battery level is ${manufacturerSpecificValues["BatteryPct"]}% (${manufacturerSpecificValues["BatteryVolts"]} Volts)"
		]
    }

    if (manufacturerSpecificValues.containsKey("Temperature")) {
        events += [
			name: 'temperature',
			value: manufacturerSpecificValues["Temperature"],
			unit: "°C",
			isStateChange: true,
			descriptionText: "Temperature is ${manufacturerSpecificValues["Temperature"]}°C"
		]
    }

	if (manufacturerSpecificValues?.containsKey("RouterID")) {
		state.routerID = manufacturerSpecificValues["RouterID"].toUpperCase()
	}

    return events
}

private parseXiaomiReport_FF01(payload) {
	displayDebugLog("Xiaomi parse FF01 string = ${payload}")
    
    def values = [ : ]

    def msgPos = 0
	def msgLength = payload.size()

    while (msgPos < msgLength) {
        def dataTag = Integer.parseInt(payload[msgPos++..msgPos++], 16)
        def dataType = Integer.parseInt(payload[msgPos++..msgPos++], 16)
        def dataLen = DataType.getLength(dataType)

        if (dataLen == null || dataLen == -1) { // Probably variable length
            switch (dataType) {
                case DataType.STRING_OCTET:
                case DataType.STRING_CHAR:
			        dataLen = Integer.parseInt(payload[msgPos++..msgPos++], 16)
                	break;
                case DataType.STRING_LONG_OCTET:
                case DataType.STRING_LONG_CHAR:
			        dataLen = toBigEndian(payload[msgPos++..(msgPos+=3)-1])
                	break;
	            default:
    	            log.error("Unsupported data type in Xiaomi Report msg: attrID = 0x${Integer.toHexString(attrId)}, type = 0x${Integer.toHexString(dataType)}")
        	        return
            }
        }

        def dataPayload

        if (dataLen != 0)
        	dataPayload = payload[msgPos++..(msgPos+=(dataLen * 2) - 1)-1]
        
        switch (dataTag) {
            case 0x01: // Battery
                def rawVolts = toBigEndian(dataPayload) / 1000
				def minVolts = voltsmin ? voltsmin : 2.5
				def maxVolts = voltsmax ? voltsmax : 3.0
				def pct = (rawVolts - minVolts) / (maxVolts - minVolts)
				def roundedPct = Math.min(100, Math.round(pct * 100))

            	values += [ BatteryPct : roundedPct ]
            	values += [ BatteryVolts : rawVolts ]
            	break;
            case 0x03: // Temperature
            	values += [ Temperature : Integer.parseInt(dataPayload, 16) - 1 ]  // Just a guess :)
            	break;
            case 0x05: // RSSI?
            	values += [ RSSI : (toBigEndian(dataPayload) / 10) - 90 ]
            	break;
            case 0x06: // LQI?
            	values += [ LQI : 255 - toBigEndian(dataPayload) ]
            	break;
            case 0x0A: // router
            	values += [ RouterID : Integer.toHexString(toBigEndian(dataPayload)) ]
            	break;
            case 0x64: // current state
				def contactVal = parseContact(toBigEndian(dataPayload))
				if (contactVal.isStateChange)
            		values += contactVal
				else
					displayDebugLog("Current state equals to previously set value, not setting again") // Probably this is what isStateChange is for, but let's stay in the safety zone, and skip this event
            	break;
            default:
		        log.warn("Unsupported tag in Xiaomi Report msg: dataTag = 0x${Integer.toHexString(dataTag)}, type = 0x${Integer.toHexString(dataType)}, length = ${dataLen} bytes, payload = ${dataPayload}")
        }
    }
    
    return values;
}

private parseXiaomiReport_0005(payload) {
	displayDebugLog("Xiaomi parse 0005 string = ${payload}")
    
    return new String(payload.decodeHex())
}

// Manually override contact state to closed
def resetToClosed() {
	if (device.currentState('contact')?.value == "open") {
		displayInfoLog("Manually reset to closed")
		sendEvent(parseContact(0))
	}
}

// Manually override contact state to open
def resetToOpen() {
	if (device.currentState('contact')?.value == "closed") {
		displayInfoLog("Manually reset to open")
		sendEvent(parseContact(1))
	}
}

private def displayDebugLog(message) {
	if (debugLogging) log.debug "${device.displayName}: ${message}"
}

private def displayInfoLog(message) {
	if (infoLogging || state.prefsSetCount != 1)
		log.info "${device.displayName}: ${message}"
}

//Reset the batteryLastReplaced date to current date
def resetBatteryReplacedDate(paired) {
	def newlyPaired = paired ? " for newly paired sensor" : ""
	sendEvent(name: "batteryLastReplaced", value: new Date())
	displayInfoLog("Setting Battery Last Replaced to current date${newlyPaired}")
}

// installed() runs just after a sensor is paired
def installed() {
	displayInfoLog("Installing")
	state.prefsSetCount = 0
}

// configure() runs after installed() when a sensor is paired or reconnected
def configure() {
	displayInfoLog("Configuring")
	init()
	state.prefsSetCount = 1
	return
}

// updated() will run every time user saves preferences
def updated() {
	displayInfoLog("Updating preference settings")
	init()
	displayInfoLog("Info message logging enabled")
	displayDebugLog("Debug message logging enabled")
}

def init() {
	if (!device.currentState('batteryLastReplaced')?.value)
		resetBatteryReplacedDate(true)
}
