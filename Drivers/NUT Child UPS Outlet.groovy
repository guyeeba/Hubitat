/**
 *  NUT Child UPS Outlet Device Type for Hubitat
 *  Péter Gulyás (@guyeeba)
 *
 *  Usage:
 *  See NUT UPS Driver
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
 */

metadata {
    definition (name: "NUT Child UPS Outlet", namespace: "guyee", author: "Péter Gulyás") {
        capability "Refresh"
		capability "Switch"
		
		// Outlet capabilities
		capability "VoltageMeasurement"
		capability "PowerMeter"		
	}
	
	attribute "switchable", "Boolean"
}

def refresh() {
    parent.refresh()
}

def parseOUTLET(String[] msg, String value) {
	switch (msg[0]) {
		case "id":
		break;
		case "status":
			Boolean isOn = value == "on"
			sendEvent( [
				name: 'switch',
				value: value ? "on" : "off",
				descriptionText: "Outlet status is ${value}"
			])
			break;			
		case "switchable":
			Boolean isSwitchable = (value == "yes")
			sendEvent( [
				name: 'switchable',
				value: isSwitchable,
				descriptionText: "Outlet is ${isSwitchable ? "" : "not"} switchable"
			])
			break;			
		default:
			log.error("ParseOUTLET: Couldn't process message: \"${msg}\"")
	}
}
