# osmosis-nls-dem

osmosis-nls-dem is an [@Osmosis](https://github.com/openstreetmap/osmosis) plugin for adding height information to OSM nodes based on open digital elevation model (DEM) data from [National Land Survey (NLS) of Finland](http://www.maanmittauslaitos.fi/en/professionals/digital-products/datasets-free-charge/acquisition-nls-open-data).

## Prerequisites

The plugin requires [The Java Advanced Imaging API (JAI)](http://www.oracle.com/technetwork/java/javase/tech/jai-142803.html) and Image I/O to work with `.tiff`-files. The installation instructions can be seen [here](http://geoserver.geo-solutions.it/edu/en/install_run/jai_io_install.html) or [here](JAI install guide).

## Build

To build `osmosis-nls-dem` from source, run:

    git clone https://github.com/jsimomaa/osmosis-nls-dem.git
    cd osmosis-nls-dem
    mvn install

You can find the built JAR from `osmosis-nls-dem/target/osmosis-nls-dem-${version}.jar`

## Installation

To install the plugin into Osmosis just copy the JAR in `~/.openstreetmap/osmosis/plugins/` and you are ready to go.

## Usage

You can reference the plugin in your workflow with the `nls-dem` alias.

    osmosis --read-pbf finland-latest.osm.pbf --nls-dem apiKey=<api_key> --write-xml output.xml

| Option          | Description                                                                                                        | Valid values                 | Default value                                  |
| --------------- | ------------------------------------------------------------------------------------------------------------------ | -----------------------------| ---------------------------------------------- |
| **apiKey**      | NLS API key for file service. Get from [here](https://tiedostopalvelu.maanmittauslaitos.fi/tp/mtp/tilaus?lang=en). |                              |                                                |
| prjFile         | Path to a `.prj`-file with WKT containing EPSG:3067.                                                              | Path to existing file        | [EPSG3067.prj](src/main/resources/EPSG3067.prj)|
| tiffStorage     | Path for storing the `tiff`-files downloaded from NLS API                                                         | Path to existing file        | `java.io.tmpdir`                             |
| heightTags      | Tags to interpret as existing height tags for OSM nodes                                                            | Tags separate by comma (`,`) | `""`                                          |
| override        | Should existing height tags be overriden with the data collected from corresponding `tiff`-file.                  | true, false                  | true                                           |

## License

See [LICENSE](LICENSE)