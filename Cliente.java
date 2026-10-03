import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

/**
 * PASO 1 - CLIENTE
 * Se ejecuta en el ordenador desde el que se controla.
 * Busca los hosts de la red local, deja elegir uno y muestra su pantalla.
 */
public class Cliente extends JFrame {

    private static final int PUERTO = 5000;
    private static final int PUERTO_DESCUBRIMIENTO = 5001;
    private static final String MENSAJE_BUSQUEDA = "BUSCAR_HOST";
    private static final String PREFIJO_RESPUESTA = "HOST";
    private static final int TIEMPO_BUSQUEDA_MS = 2000;
    private static final String OPCION_MANUAL = "Escribir la IP a mano...";

    private volatile BufferedImage imagenActual;
    private int anchoRemoto;
    private int altoRemoto;

    /** false = ajustar a la ventana, true = píxeles reales (con barras de desplazamiento) */
    private boolean tamanoReal = false;

    private final PanelPantalla panel = new PanelPantalla();
    private final JScrollPane scroll = new JScrollPane(panel);

    public Cliente() {
        super("Escritorio remoto");

        JToggleButton botonTamano = new JToggleButton("Tamaño real (100%)");
        botonTamano.setToolTipText("Muestra la pantalla sin reducir, para leer mejor el texto");
        botonTamano.addActionListener(e -> {
            tamanoReal = botonTamano.isSelected();
            panel.revalidate();
            panel.repaint();
        });

        JToolBar barra = new JToolBar();
        barra.setFloatable(false);
        barra.add(botonTamano);

        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        scroll.getHorizontalScrollBar().setUnitIncrement(20);

        add(barra, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        setSize(1280, 760);
        setLocationRelativeTo(null);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
    }

    /** Panel que pinta la pantalla remota con buena calidad. */
    private class PanelPantalla extends JPanel {

        PanelPantalla() {
            setBackground(Color.BLACK);
        }

        /**
         * Escala de Windows del ordenador del cliente (1.0 = 100%, 1.5 = 150%...).
         * La usamos para que "tamaño real" sea 1 píxel remoto = 1 píxel de tu pantalla.
         */
        private double escalaSistema() {
            GraphicsConfiguration config = getGraphicsConfiguration();
            return config == null ? 1.0 : config.getDefaultTransform().getScaleX();
        }

        @Override
        public Dimension getPreferredSize() {
            BufferedImage imagen = imagenActual;
            if (tamanoReal && imagen != null) {
                double escala = escalaSistema();
                return new Dimension((int) Math.ceil(imagen.getWidth() / escala),
                        (int) Math.ceil(imagen.getHeight() / escala));
            }
            // Modo ajustar: ocupa justo el hueco visible, sin barras
            return scroll.getViewport().getExtentSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            BufferedImage imagen = imagenActual;

            if (imagen == null) {
                g.setColor(Color.WHITE);
                g.drawString("Esperando a que el otro equipo acepte la conexión...", 20, 30);
                return;
            }

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int ancho;
            int alto;
            if (tamanoReal) {
                double escala = escalaSistema();
                ancho = (int) Math.round(imagen.getWidth() / escala);
                alto = (int) Math.round(imagen.getHeight() / escala);
            } else {
                // Ajustar manteniendo la proporción, sin deformar la imagen
                double factor = Math.min((double) getWidth() / imagen.getWidth(),
                        (double) getHeight() / imagen.getHeight());
                ancho = (int) Math.round(imagen.getWidth() * factor);
                alto = (int) Math.round(imagen.getHeight() * factor);
            }

            int x = Math.max(0, (getWidth() - ancho) / 2);
            int y = Math.max(0, (getHeight() - alto) / 2);
            g2.drawImage(imagen, x, y, ancho, alto, null);
            g2.dispose();
        }
    }

    /** Conecta en un hilo aparte para no bloquear la ventana. */
    private void conectar(String ip) {
        new Thread(() -> {
            try (Socket socket = new Socket(ip, PUERTO);
                 DataInputStream entrada = new DataInputStream(
                         new BufferedInputStream(socket.getInputStream()))) {

                anchoRemoto = entrada.readInt();
                altoRemoto = entrada.readInt();
                SwingUtilities.invokeLater(() ->
                        setTitle("Escritorio remoto - " + ip + " (" + anchoRemoto + "x" + altoRemoto + ")"));

                while (true) {
                    int tamano = entrada.readInt();
                    byte[] bytes = new byte[tamano];
                    entrada.readFully(bytes);

                    BufferedImage nueva = ImageIO.read(new ByteArrayInputStream(bytes));
                    BufferedImage anterior = imagenActual;
                    imagenActual = nueva;

                    boolean cambiaTamano = anterior == null
                            || anterior.getWidth() != nueva.getWidth()
                            || anterior.getHeight() != nueva.getHeight();
                    SwingUtilities.invokeLater(() -> {
                        if (cambiaTamano) panel.revalidate();
                        panel.repaint();
                    });
                }
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "Conexión terminada o rechazada.\n" + e.getMessage()));
            }
        }).start();
    }

    // ------------------------------------------------------------------
    // Búsqueda de equipos en la red local
    // ------------------------------------------------------------------

    /** Un ordenador encontrado (puede haber respondido desde varias IPs). */
    private static class EquipoEncontrado {
        final String nombre;
        final String ipPrincipal;
        final Set<String> ipsQueRespondieron = new LinkedHashSet<>();

        EquipoEncontrado(String nombre, String ipPrincipal) {
            this.nombre = nombre;
            this.ipPrincipal = ipPrincipal;
        }

        /** La IP buena: la principal que nos dice el host; si no la sabe, la primera que respondió. */
        String ipValida() {
            return ipPrincipal.isEmpty() ? ipsQueRespondieron.iterator().next() : ipPrincipal;
        }
    }

    /**
     * Envía "BUSCAR_HOST" por broadcast a toda la red local y recoge las respuestas
     * durante unos segundos. Agrupa por el id del host, así cada PC sale una sola vez.
     */
    private static List<EquipoEncontrado> buscarHosts() {
        Map<String, EquipoEncontrado> porId = new LinkedHashMap<>();
        byte[] mensaje = MENSAJE_BUSQUEDA.getBytes(StandardCharsets.UTF_8);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);

            for (InetAddress destino : direccionesBroadcast()) {
                try {
                    socket.send(new DatagramPacket(mensaje, mensaje.length, destino, PUERTO_DESCUBRIMIENTO));
                } catch (IOException ignorada) {
                    // Algunas interfaces no permiten broadcast: seguimos con las demás
                }
            }

            byte[] buffer = new byte[512];
            long fin = System.currentTimeMillis() + TIEMPO_BUSQUEDA_MS;

            while (System.currentTimeMillis() < fin) {
                socket.setSoTimeout((int) Math.max(1, fin - System.currentTimeMillis()));
                DatagramPacket respuesta = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(respuesta);
                } catch (SocketTimeoutException tiempoAgotado) {
                    break;
                }

                // Formato: HOST|id|ipPrincipal|nombre
                String texto = new String(respuesta.getData(), 0, respuesta.getLength(), StandardCharsets.UTF_8);
                String[] partes = texto.split("\\|", 4);
                if (partes.length != 4 || !PREFIJO_RESPUESTA.equals(partes[0])) continue;

                EquipoEncontrado equipo = porId.computeIfAbsent(partes[1],
                        id -> new EquipoEncontrado(partes[3], partes[2]));
                equipo.ipsQueRespondieron.add(respuesta.getAddress().getHostAddress());
            }
        } catch (IOException e) {
            System.out.println("Error buscando equipos: " + e.getMessage());
        }
        return new ArrayList<>(porId.values());
    }

    /** Broadcast general + el broadcast de cada tarjeta de red (WiFi, cable...). */
    private static List<InetAddress> direccionesBroadcast() {
        List<InetAddress> lista = new ArrayList<>();
        try {
            lista.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface red : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!red.isUp() || red.isLoopback()) continue;
                for (InterfaceAddress direccion : red.getInterfaceAddresses()) {
                    if (direccion.getBroadcast() != null) {
                        lista.add(direccion.getBroadcast());
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("No se pudieron leer las tarjetas de red: " + e.getMessage());
        }
        return lista;
    }

    /** Busca equipos y deja elegir uno. Devuelve la IP elegida o null si se cancela. */
    private static String elegirEquipo() {
        List<EquipoEncontrado> equipos = buscarHosts();

        if (equipos.isEmpty()) {
            return JOptionPane.showInputDialog(null,
                    "No se ha encontrado ningún equipo con el Host abierto.\nEscribe la IP a mano:",
                    "Escritorio remoto", JOptionPane.QUESTION_MESSAGE);
        }

        List<String> opciones = new ArrayList<>();
        for (EquipoEncontrado equipo : equipos) {
            opciones.add(equipo.nombre + "  (" + equipo.ipValida() + ")");
        }
        opciones.add(OPCION_MANUAL);

        Object elegido = JOptionPane.showInputDialog(null,
                "Equipos encontrados en tu red:",
                "Escritorio remoto",
                JOptionPane.QUESTION_MESSAGE,
                null,
                opciones.toArray(),
                opciones.get(0));

        if (elegido == null) return null;
        if (OPCION_MANUAL.equals(elegido)) {
            return JOptionPane.showInputDialog(null, "IP del ordenador a controlar:");
        }
        return equipos.get(opciones.indexOf(elegido)).ipValida();
    }

    public static void main(String[] args) {
        String ip = elegirEquipo();
        if (ip == null || ip.isBlank()) return;

        SwingUtilities.invokeLater(() -> {
            Cliente cliente = new Cliente();
            cliente.setVisible(true);
            cliente.conectar(ip.trim());
        });
    }
}