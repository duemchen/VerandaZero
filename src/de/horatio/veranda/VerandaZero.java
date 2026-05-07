/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Main.java to edit this template

Nutzung gpio0 gpio1
in config.txt setze
force_eeprom_read=0

dtoverlay=w1-gpio,gpiopin=4
#4, 17
 */
package de.horatio.veranda;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.event.ShutdownEvent;
import com.pi4j.event.ShutdownListener;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.gpio.digital.PullResistance;
import de.horatio.common.HoraFile;
import de.horatio.common.HoraIni;
import java.io.IOException;
import static java.lang.Thread.sleep;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import se.hirt.w1.Sensor;
import se.hirt.w1.Sensors;

/**
 *
 * @author duemchen
 */
public class VerandaZero {

    private static final int PIN_LED = 22; // PIN 15 = BCM 22
    private static final org.apache.logging.log4j.Logger log = org.apache.logging.log4j.LogManager.getLogger();
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH");
    private static final DecimalFormat df = new DecimalFormat("0.00");
    final static String veranda_ini = "veranda.ini";
    private static MqttVeranda mqttTemperaturen;
    private FussbodenHeizung fusshzg;
    private final int TEMPERATUR_POLLING_SEK = 4; //(war 2)
    private AtomicInteger soll;
    private static Context pi4j = Pi4J.newAutoContext();

    private boolean aussentempToPumpe() {
        double temp = -11;
        try {
            // openweather.OpenWeather ow = new OpenWeather();
            double lon = 12.89;
            double lat = 53.09;
            //  ow.setCoord(lon, lat);
            //  temp = ow.getTemp();
        } catch (Exception e) {

        }
        log.debug("aussentemp: " + temp);
        /*
        boolean result = true;
        if (temp > AUSSENTEMPERATURGRENZE) {
            result = false;
            DA.PUMPE.off();

        } else {
            DA.PUMPE.on();
        }
         */

        return false;

    }

    /**
     * iii
     */
    public static enum DA {

        STELLHOT(17), //0  wiringPi 0-6  umbenannt auf bcm, bcm = GPIOXX
        STELLIMP(18), //1
        PUMPE(27), //2
        A(22),//3
        B(23),//4
        C(24), //5
        D(25), //6
        LIVE(8),; //7 10 

        //   DigitalOutput led = pi4j.digitalOutput().create(PIN_LED);
        private DigitalOutput pin;

        private DA(int pin) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {

            }
            var config = DigitalOutput.newConfigBuilder(pi4j)
                    .bcm(pin)                                        
                    .shutdown(DigitalState.LOW)
                    //.provider("pigpio-digital-output")
                    .initial(DigitalState.HIGH)
                    .build()
                    ;

            this.pin = pi4j.create(config);//e(pin); //  gpio.provisionDigitalOutputPin(pin, "", PinState.HIGH);

        }

        public DigitalOutput getPin() {
            return pin;
        }

        @Override
        public String toString() {
            String s = super.toString();
            return s.substring(0, 1) + s.substring(1).toLowerCase();
        }

        public void on() {
            pin.low();
            //System.out.println(name() + " on");
        }

        public void off() {
            pin.high();
            //System.out.println(name() + " off");

        }

        public boolean isOff() {
            return pin.isHigh();

        }

        public boolean isOn() {
            return pin.isLow();
        }

    }

    /**
     * jeder fühler wird aktiviert wenn er beim start gefunden wurde - alle IDs
     * sammeln in Hash, sortieren und speichern - laden *
     */
    public enum TEMP {

        VORLAUF,
        RUECKLAUF;
        private Sensor sensor = null;
        private Number lastTemp = 0.0;
        private Number middleTemp = 0.0;

        public void setSensor(Sensor sensor) {
            this.sensor = sensor;
        }

        public Number getTemp() {
            if (sensor == null) {
                return 0;
            }
            try {
                Number iTemp = sensor.getValue();
                //Nullunterdrückung
                if (iTemp != null) {
                    if (iTemp.longValue() >= -10) {
                        if (iTemp.longValue() < 100) {
                            Number middle = mittelWertGewichtet(middleTemp, iTemp, 3.0);
                            // der middleTemp ist immer nahe der ist
                            // wenn also mal ein Ausbrecher kommt, kann middleTemp in diese Richtung verschoben werden.
                            // es heilt sich dann sofort wieder
                            double MAXDELTA = 5.0;
                            double delta = iTemp.doubleValue() - middle.doubleValue();
                            if (Math.abs(delta) < MAXDELTA) {
                                lastTemp = iTemp;
                                middleTemp = middle;
                                log.debug(this.name() + "\tnewVal=" + lastTemp + ", middleTemp=" + df.format(middleTemp) + ", delta=" + df.format(delta));
                            } else {
                                if (delta >= MAXDELTA) {
                                    delta = MAXDELTA * 0.9;
                                } else {
                                    if (delta <= -MAXDELTA) {
                                        delta = -MAXDELTA * 0.9;
                                    }
                                }
                                middleTemp = middleTemp.doubleValue() + delta;
                                log.debug("Ausbrecher " + this.name() + "\tist=" + iTemp + ", middleTemp=" + df.format(middleTemp) + ", delta=" + df.format(delta));
                            }
                        }
                    }
                }
                return lastTemp;

            } catch (IOException ex) {
                log.error("getTemp()");
                log.error(ex);
            }

            return null;
        }

        public Number getTempLast() {
            return lastTemp;
        }

        @Override
        public String toString() {
            return "TemperaturSensor " + this.name() + ", sensor:" + sensor;
        }

        private Number mittelWertGewichtet(Number middle, Number neu, double wichtung) {
            try {
                double result = wichtung * middle.doubleValue() + neu.doubleValue();
                result = result / (wichtung + 1);
                return result;

            } catch (Exception e) {
                log.error("mittel");
                log.error(e);
            }
            return middle;
        }

    }

    private static boolean initTemperatureSensoren() {
        try {

            System.out.println("inifile: " + HoraFile.getCanonicalPath(veranda_ini));
            Set<se.hirt.w1.Sensor> sensors = Sensors.getSensors();
            System.out.println(String.format("Found %d sensors:", sensors.size()));
            // jeder Sensor wird mit ID in eine Propertydatei eingetragen.
            // dort erfolgt die Zuordnung zu dem konkreten Sensor
            // FERN ID = 28-000000e46c60
            for (Sensor sensor : sensors) {
                System.out.println(String.format("%s(%s):%3.2f%s",
                        sensor.getPhysicalQuantity(), sensor.getID(),
                        sensor.getValue(), sensor.getUnitString()));
                HoraIni.SchreibeIniString(veranda_ini, "SensorIDs", sensor.getID(), String.format("%3.2f", sensor.getValue()));

            }
            System.out.println(String.format("Zugeordnete Sensoren:", sensors.size()));
            for (TEMP temp : TEMP.values()) {
                String id = HoraIni.LeseIniString(veranda_ini, "Temperaturen", temp.name(), "", true);
                if ("".equals(id)) {
                    continue;
                }
                // diese ID in sensoren suchen und verschalten
                //System.out.println(id);
                for (Sensor sensor : sensors) {
                    //System.out.println(id + " " + sensor.getID());
                    if (!id.equalsIgnoreCase(sensor.getID())) {
                        continue;
                    }
                    temp.setSensor(sensor);
                    break;
                }
            }
            // alle angebunden?

            for (TEMP temp : TEMP.values()) {
                System.out.println(temp);
            }
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    public VerandaZero() throws InterruptedException, IOException {

        MqttVeranda.getInstance().sendHallo();
        // add shutdown listener
        pi4j.addListener(new ShutdownListener() {

            @Override
            public void beforeShutdown(ShutdownEvent event) {
                //TODO led.low();
                System.out.println("Pi4J RUNTIME EVENT --> BEFORE SHUTDOWN EVENT");
            }

            @Override
            public void onShutdown(ShutdownEvent event) {
                System.out.println("Pi4J RUNTIME EVENT --> (AFTER) SHUTDOWN EVENT");
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                try {
                    fusshzg.interrupt();
                    fusshzg.join(1000);

                } catch (InterruptedException ex) {
                    log.error(ex);
                }

                for (DA da : DA.values()) {
                    da.off();
                }
                MqttVeranda.getInstance().sendByebye();
                System.out.println("addShutdownHook. End.");
                log.info("addShutdownHook. End.");

            }
        });

        while (!initTemperatureSensoren()) {
            sleep(2000);
            log.debug("repeat initTemperatureSensoren");
        }

        fusshzg = new FussbodenHeizung(soll, TEMP.VORLAUF, TEMP.RUECKLAUF, DA.STELLHOT, DA.STELLIMP, DA.PUMPE);
        fusshzg.setName("FussbodenHeizung");
        fusshzg.start();
        DA.PUMPE.on();

        int i = 0;
        int lastHour = -1;
        while (true) {
            Thread.sleep(TEMPERATUR_POLLING_SEK * 1000);
            //
            String s = sdf.format(new Date());
            Integer hour = Integer.parseInt(s);
            if (lastHour != hour.intValue()) {
                lastHour = hour.intValue();
                // pumpe abschalten wenn temperatur > 15 grad ist.
                // boolean heizen = aussentempToPumpe();
                //fusshzg.setRegler(heizen);

            }

//
            if (DA.LIVE.isOn()) {
                DA.LIVE.off();
            } else {
                DA.LIVE.on();
            }

            for (TEMP temp : TEMP.values()) {
                temp.getTemp();
            }

            if (i % 2 == 0) {
                try {
                    //TODO minütliche info ans MQTT, nach minutenwechsel
                    MqttVeranda.getInstance().sendTemps();
                } catch (Exception ex) {
                    log.error(ex);
                }
            }
            i++;
        }
    }

    public static void main(String[] args) throws IOException {

        log.info("Start Veranda Zero.");
        log.info(HoraFile.getCanonicalPath("log4j.xml"));
        try {
            VerandaZero veranda = new VerandaZero();
        } catch (IOException | InterruptedException e) {
            log.error("main meldet:");
            log.error(e);
            log.error("Programmende.");
        }
    }

}
