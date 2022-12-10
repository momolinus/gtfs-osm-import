/**
 Licensed under the GNU General Public License version 3
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.gnu.org/licenses/gpl-3.0.html

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.

 **/
package it.osm.gtfs.command;

import it.osm.gtfs.input.GTFSParser;
import it.osm.gtfs.input.OSMParser;
import it.osm.gtfs.model.BoundingBox;
import it.osm.gtfs.model.Stop;
import it.osm.gtfs.model.Stop.GTFSStop;
import it.osm.gtfs.output.OSMBusImportGenerator;
import it.osm.gtfs.utils.GTFSImportSettings;
import it.osm.gtfs.utils.OSMDistanceUtils;
import it.osm.gtfs.utils.OSMXMLUtils;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

import org.fusesource.jansi.Ansi;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import picocli.CommandLine;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import static org.fusesource.jansi.Ansi.ansi;


@CommandLine.Command(name = "stops", description = "Generate files to import bus stops into osm merging with existing stops")
public class GTFSGenerateBusStopsImport implements Callable<Void> {

    @Override
    public Void call() throws IOException, ParserConfigurationException, SAXException, TransformerException {
        List<GTFSStop> gtfsStops = GTFSParser.readBusStop(GTFSImportSettings.getInstance().getGTFSPath() + GTFSImportSettings.GTFS_STOP_FILE_NAME);
        BoundingBox bb = new BoundingBox(gtfsStops);

        List<Stop> osmStops = OSMParser.readOSMStops(GTFSImportSettings.getInstance().getOSMPath() + GTFSImportSettings.OSM_STOP_FILE_NAME);

        //fase di matching delle fermate di OSM con quelle GTFS - in particolare la funzione matches() ritorna true se due fermate sono le stesse secondo l'algoritmo della funzione
        for (GTFSStop gtfsStop : gtfsStops){
            for (Stop osmStop : osmStops){
                if (gtfsStop.matches(osmStop)){
                    if (osmStop.isStopPosition()){
                        if (osmStop.stopMatchedWith != null){
                            System.err.println("Mupliple match found.");
                            System.err.println(" OSM: " + osmStop);
                            System.err.println("GTFS: " + gtfsStop);
                            System.err.println(" OSM: " + gtfsStop.railwayMatchedWith);
                            System.err.println("GTFS: " + osmStop.stopMatchedWith);
                            throw new IllegalArgumentException("Multiple match found, this is currently unsupported.");
                        }

                        gtfsStop.stopPositionsMatchedWith.add(osmStop);
                        osmStop.stopMatchedWith = gtfsStop;
                    }else if (osmStop.isRailway()){
                        if (gtfsStop.railwayMatchedWith != null || osmStop.stopMatchedWith != null){
                            System.err.println("Mupliple match found.");
                            System.err.println(" OSM: " + osmStop);
                            System.err.println("GTFS: " + gtfsStop);
                            System.err.println(" OSM: " + gtfsStop.railwayMatchedWith);
                            System.err.println("GTFS: " + osmStop.stopMatchedWith);
                            throw new IllegalArgumentException("Multiple match found, this is currently unsupported.");
                        }

                        gtfsStop.railwayMatchedWith = osmStop;
                        osmStop.stopMatchedWith = gtfsStop;
                    }else{
                        if (gtfsStop.stopMatchedWith != null || osmStop.stopMatchedWith != null){
                            System.err.println("Mupliple match found.");
                            System.err.println(" OSM: " + osmStop);
                            System.err.println("GTFS: " + gtfsStop);
                            System.err.println(" OSM: " + gtfsStop.stopMatchedWith);
                            System.err.println("GTFS: " + osmStop.stopMatchedWith);
                            throw new IllegalArgumentException("Multiple match found, this is currently unsupported.");
                        }

                        gtfsStop.stopMatchedWith = osmStop;
                        osmStop.stopMatchedWith = gtfsStop;
                    }

                }
            }
        }

        //Stops matched with gtfs_id (also checking stops no longer in GTFS)
        {
            //FIXME: check if all tags of the node are in line with GTFS data (name, accessibility ecc)

            //TODO: probably the matched_stops counter can be moved and incremented in the first matching phase up there
            int matched_stops = 0;
            int not_matched_osm_stops = 0;
            int osm_with_different_gtfs_id = 0; //this can be removed as it serves no purpose actually
            OSMBusImportGenerator bufferNotMatchedStops = new OSMBusImportGenerator(bb);
            OSMBusImportGenerator bufferMatchedStops = new OSMBusImportGenerator(bb);

            //TODO: non ho capito esattamente perche' si utilizza questo per salvare i messaggi per poi stamparli solamente una volta in un ciclo for
            Map<Double, String> messages = new TreeMap<Double, String>();
            for (Stop osmStop : osmStops){
                if (osmStop.stopMatchedWith != null){
                    Element n = (Element) osmStop.originalXMLNode;

                    //non so perche' sia importate la distanza tra le due fermate qua
                    double dist = OSMDistanceUtils.distVincenty(osmStop.getLat(), osmStop.getLon(), osmStop.stopMatchedWith.getLat(), osmStop.stopMatchedWith.getLon());
                    if (dist > 5){
                        messages.put(dist, "Stop ref " + osmStop.getCode() +
                                " distance GTFS-OSM: " + OSMDistanceUtils.distVincenty(osmStop.getLat(), osmStop.getLon(), osmStop.stopMatchedWith.getLat(), osmStop.stopMatchedWith.getLon()) + " m");
                    }



                    if (!osmStop.stopMatchedWith.getGtfsId().equals(osmStop.getGtfsId())){
                        osm_with_different_gtfs_id++;

                        OSMXMLUtils.addTagOrReplace(n, "gtfs_id", osmStop.stopMatchedWith.getGtfsId());

                        System.out.println("OSM Stop node id " + osmStop.getOSMId() + " (ref " + osmStop.getCode() + ")" + " has gtfs_id: " + osmStop.getGtfsId() + " but in GTFS has gtfs_id: " + osmStop.stopMatchedWith.getGtfsId());
                    }

                    //TODO: check if the name changed & other tags
                    OSMXMLUtils.addTagIfNotExisting(n, "name", GTFSImportSettings.getInstance().getPlugin().fixBusStopName(osmStop.stopMatchedWith.getName()));
                    OSMXMLUtils.addTagIfNotExisting(n, "operator", GTFSImportSettings.getInstance().getOperator());

                    OSMXMLUtils.addTagIfNotExisting(n, GTFSImportSettings.getInstance().getRevisedKey(), "no");

                    //TODO: to add the wheelchair:description tag also per wiki https://wiki.openstreetmap.org/wiki/Key:wheelchair#Public_transport_stops/platforms
                    OSMXMLUtils.addTagIfNotExisting(n, "wheelchair", osmStop.getWheelchairAccessibility().getOsmValue());

                    if(osmStop.isRailway()) {
                        //OSMXMLUtils.addTagIfNotExisting(n, "tram", "yes");
                        OSMXMLUtils.addTagIfNotExisting(n, "public_transport", "stop_position");
                    } else {
                        OSMXMLUtils.addTagIfNotExisting(n, "bus", "yes");
                        OSMXMLUtils.addTagIfNotExisting(n, "highway", "bus_stop");
                        OSMXMLUtils.addTagIfNotExisting(n, "public_transport", "platform");
                    }

                    //add the node to the buffer of matched stops
                    bufferMatchedStops.appendNode(n);
                    matched_stops++;
                }else{
                    not_matched_osm_stops++;
                    System.out.println("OSM Stop node id " + osmStop.getOSMId() + " (ref " + osmStop.getCode() + ")" + " has gtfs_id: " + osmStop.getGtfsId() + " but the stop didn't get matched to a GTFS stop as they are too distant or ref code is no more available.");
                    Element n = (Element) osmStop.originalXMLNode;

                    //add the node to the buffer of not matched stops
                    bufferNotMatchedStops.appendNode(n);
                }
            }

            for(String msg:messages.values())
                System.out.println(msg);

            if (matched_stops > 0){
                bufferMatchedStops.end();
                bufferMatchedStops.saveTo(new FileOutputStream(GTFSImportSettings.getInstance().getOutputPath() + GTFSImportSettings.OUTPUT_MATCHED_WITH_UPDATED_METADATA));
                System.out.println(ansi().fg(Ansi.Color.GREEN).a("Matched OSM stops with GTFS data with updated metadata applied (new gtfs ids, names etc.): ").reset().a(matched_stops).fg(Ansi.Color.YELLOW).a(" (created josm osm change file to review data: " + GTFSImportSettings.OUTPUT_MATCHED_WITH_UPDATED_METADATA + ")"));
            }else{
                System.out.println(ansi().fg(Ansi.Color.YELLOW).a("No OSM stop got matched with GTFS data!").reset());
            }

            if (not_matched_osm_stops > 0){
                bufferNotMatchedStops.end();
                bufferNotMatchedStops.saveTo(new FileOutputStream(GTFSImportSettings.getInstance().getOutputPath() + GTFSImportSettings.OUTPUT_NOT_MATCHED_STOPS));
                System.out.println(ansi().fg(Ansi.Color.GREEN).a("Stops NOT MATCHED that need to be *removed* from OSM: ").reset().a(not_matched_osm_stops).fg(Ansi.Color.YELLOW).a(" (created josm osm change file to review data: " + GTFSImportSettings.OUTPUT_NOT_MATCHED_STOPS + ")"));
            }
        }

        //new stops from gtfs data
        {
            int new_stops_from_gtfs = 0;
            OSMBusImportGenerator buffer = new OSMBusImportGenerator(bb);

            for (GTFSStop gtfsStop:gtfsStops){
                if (gtfsStop.stopMatchedWith == null && gtfsStop.railwayMatchedWith == null && gtfsStop.stopPositionsMatchedWith.size() == 0){
                    new_stops_from_gtfs++;
                    buffer.appendNode(gtfsStop.getNewXMLNode(buffer));


                }
            }
            buffer.end();
            if (new_stops_from_gtfs > 0){
                buffer.saveTo(new FileOutputStream(GTFSImportSettings.getInstance().getOutputPath() + GTFSImportSettings.OUTPUT_NEW_STOPS_FROM_GTFS + ".osm"));
                System.out.println(ansi().fg(Ansi.Color.GREEN).a("New stops from GTFS (unmatched stops from GTFS): ").reset().a(new_stops_from_gtfs).fg(Ansi.Color.YELLOW).a(" (created josm osm change file to import data: " + GTFSImportSettings.OUTPUT_NEW_STOPS_FROM_GTFS + ".osm)").reset());
            }else{
                System.out.println(ansi().fg(Ansi.Color.GREEN).a("New stops from GTFS (unmatched stops from GTFS): ").reset().a(new_stops_from_gtfs));
            }
        }
        return null;
    }
}
