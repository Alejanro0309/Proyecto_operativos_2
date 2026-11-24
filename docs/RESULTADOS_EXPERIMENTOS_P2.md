# ÁvilaOS Proyecto 2 — Resultados experimentales (base para las conclusiones del informe)

Generados con `com.avilaos.tests.DiskExperimentsRunner` (**Run File / Shift+F6** en NetBeans).
Todas las corridas son **deterministas** (ticks manuales, sin hilos ni azar): cualquier integrante o
evaluador obtiene exactamente los mismos números.

## Experimento A — Casos P1 a P4 del enunciado

Disco de 200 bloques (0 a 199), cabezal inicial en el bloque 50 moviéndose hacia bloques mayores (`UP`),
velocidad de 10 posiciones por ciclo y las 8 solicitudes oficiales llegando en el ciclo 0
(`95, 180, 34, 119, 11, 123, 62, 64`, de 1 bloque cada una para aislar el efecto de la política de disco).

| Política | Orden de atención | Desplazamiento total | Ciclos totales | Espera prom. (ciclos) | Espera máx. (ciclos) | Utilización disco |
|---|---|---|---|---|---|---|
| FIFO | 95 → 180 → 34 → 119 → 11 → 123 → 62 → 64 | 644 | 77 | 37.50 | 76 | 100.0 % |
| SSTF | 62 → 64 → 34 → 11 → 95 → 119 → 123 → 180 | 236 | 36 | 14.63 | 30 | 100.0 % |
| SCAN | 62 → 64 → 95 → 119 → 123 → 180 → 34 → 11 | 337 | 47 | 15.25 | 44 | 100.0 % |
| C_SCAN | 62 → 64 → 95 → 119 → 123 → 180 → 11 → 34 | 183 | 31 | 13.25 | 28 | 100.0 % |

**Lectura (base para conclusiones):**
- **FIFO** es la peor en todas las métricas: al respetar estrictamente el orden de llegada, el cabezal
  cruza el disco de un extremo a otro repetidamente (`644` bloques, casi el triple que SSTF y 3.5 veces
  más que C-SCAN), duplicando el tiempo total (`77` ciclos) y elevando la espera promedio a `37.50` ciclos.
- **SSTF** reduce el desplazamiento en un 63.4 % respecto a FIFO (`236` bloques) y baja la espera promedio
  a `14.63` ciclos, al elegir siempre la solicitud más cercana al cabezal.
- **SCAN (ascensor)** evita cambios bruscos de dirección (`337` bloques y `15.25` ciclos de espera promedio),
  pero paga el recorrido obligatorio hasta el extremo superior (`199`) antes de invertir sentido hacia `34` y `11`.
- **C-SCAN** obtiene el menor desplazamiento efectivo (`183` bloques, sin contabilizar el salto de regreso
  de `199` a `0`, conforme al enunciado), el menor tiempo total (`31` ciclos) y la menor espera promedio
  (`13.25` ciclos) y máxima (`28` ciclos).
- Las cuatro políticas coinciden exactamente con los órdenes de atención esperados en los casos P1 a P4
  del enunciado oficial.

## Experimento B — Carga grande y escalonada (riesgo de inanición en SSTF)

30 solicitudes de 1 bloque con llegadas escalonadas entre los ciclos `0` y `46`, combinando peticiones
tempranas en los extremos del disco (`5, 195, 12, 188`) con un flujo sostenido en la zona central (`44–66`).
Disco de 200 bloques, cabezal inicial en `50`, velocidad `10` posiciones/ciclo.

| Política | Solicitudes atendidas | Desplazamiento total | Ciclos totales | Espera prom. (ciclos) | Espera máx. (ciclos) | Bloque con mayor espera |
|---|---|---|---|---|---|---|
| FIFO | 30 | 1554 | 200 | 86.97 | 143 | 160 |
| SSTF | 30 | 381 | 83 | 16.23 | 82 | 195 |
| SCAN | 30 | 523 | 103 | 35.03 | 80 | 5 |
| C_SCAN | 30 | 402 | 89 | 26.57 | 72 | 188 |

**Lectura (base para conclusiones):**
- **FIFO** se degrada drásticamente bajo carga intensa: recorre `1554` bloques (`200` ciclos totales) y
  acumula `86.97` ciclos de espera promedio, aunque no produce inanición porque toda solicitud avanza en orden.
- **SSTF** consigue el menor desplazamiento total (`381` bloques) y la menor espera promedio (`16.23` ciclos),
  pero evidencia claramente el **riesgo de inanición**: la solicitud al bloque `195`, que llegó en el ciclo `0`,
  quedó postergada continuamente por las llegadas cercanas a la zona media y esperó **`82` de los `83` ciclos
  totales** (fue la última solicitud atendida). En un flujo infinito en la zona media, nunca sería atendida.
- **SCAN** elimina la inanición al barrer de extremo a extremo, acotando la espera máxima (`80` ciclos en
  el bloque `5`), a costa de mayor recorrido (`523` bloques) y menor uniformidad entre extremos.
- **C-SCAN** ofrece el mejor equilibrio práctico bajo carga variada: mantiene un desplazamiento cercano a
  SSTF (`402` vs `381` bloques), elimina la inanición y logra la **menor espera máxima de todas las políticas**
  (`72` ciclos), brindando tiempos de respuesta más uniformes en todo el disco.

## Experimento C — Efecto de la suspensión de procesos

Misma carga de **10 procesos `FILE_IO`** (16 unidades de RAM cada uno, `160` unidades en total, deadline
`420` ciclos, 2 instrucciones) leyendo archivos del sistema de archivos oficial (`200` bloques, política
`SSTF`, cabezal inicial `50`, velocidad `10`, área de intercambio de `100` bloques, CPU en `FCFS`). Se
varía únicamente la capacidad de RAM del computador.

| RAM | Escenario | Ciclos totales | Completados | Deadlines vencidos | Suspensiones | Reactivaciones | Pico de swap (bloques) | Desplazamiento disco |
|---|---|---|---|---|---|---|---|---|
| 256 | Sin suspensión | 316 | 10 | 0 | 0 | 0 | 0 | 299 |
| 64 | RAM ajustada | 444 | 9 | 1 | 2 | 2 | 32 | 931 |
| 48 | RAM reducida | 449 | 8 | 2 | 3 | 3 | 32 | 817 |
| 32 | RAM crítica | 428 | 5 | 5 | 5 | 4 | 32 | 1221 |

**Lectura (base para conclusiones):**
- **Con RAM suficiente (`256`):** todos los procesos se admiten en el ciclo 1 sin recurrir al área de
  intercambio (`0` suspensiones, `0` bloques de swap). La carga termina en `316` ciclos con `100 %` de
  cumplimiento de deadlines y solo `299` bloques de desplazamiento de disco.
- **Beneficio y costo de la suspensión (`64` y `48`):** cuando la RAM no alcanza para todos los procesos,
  el planificador a mediano plazo permite mantener la multiprogramación suspendiendo procesos bloqueados
  de menor prioridad (`D1`) hacia el swap y liberando RAM para admitir nuevos procesos. Sin embargo, el
  *swapping* introduce una sobrecarga clara: cada `SUSPEND` y `REACTIVATE` compite en la misma cola del
  disco (`D5`), traslada el cabezal al inicio del área de intercambio (bloque `199`) y transfiere 16 bloques
  por operación. Esto eleva el desplazamiento del disco entre **2.7x y 3.1x** (`817`–`931` bloques) y aumenta
  el tiempo total en torno a un **40 %** (`444`–`449` ciclos), provocando los primeros deadlines vencidos (`1` y `2`).
- **Degradación bajo RAM crítica (`32`, efecto tipo *thrashing*):** con espacio solo para 2 procesos simultáneos,
  las suspensiones suben a `5`, el desplazamiento del disco se cuadruplica (`1221` bloques) y la mitad de la
  carga (`5` de `10` procesos) incumple su deadline mientras espera en disco o en swap. Además, se observan
  **5 suspensiones frente a 4 reactivaciones** porque un proceso suspendido venció su deadline en el swap
  (decisión `D7`), liberando sus bloques de intercambio sin llegar a reactivarse.
- **Conclusión práctica:** la suspensión amplía el grado de multiprogramación cuando hay procesos bloqueados
  ociosos en RAM, pero solo compensa si la RAM disponible evita un tráfico excesivo hacia el disco; con RAM
  crítica, el disco se convierte en el cuello de botella principal del sistema.