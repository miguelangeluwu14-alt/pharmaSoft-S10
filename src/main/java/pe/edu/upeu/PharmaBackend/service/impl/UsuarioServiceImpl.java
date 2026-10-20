package pe.edu.upeu.PharmaBackend.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.upeu.PharmaBackend.dto.auth.AsignarRolesRequestDTO;
import pe.edu.upeu.PharmaBackend.dto.auth.CambiarEstadoUsuarioRequestDTO;
import pe.edu.upeu.PharmaBackend.dto.auth.EmpleadoResponseDTO;
import pe.edu.upeu.PharmaBackend.dto.auth.RolResponseDTO;
import pe.edu.upeu.PharmaBackend.dto.auth.UsuarioRequestDTO;
import pe.edu.upeu.PharmaBackend.dto.auth.UsuarioResponseDTO;
import pe.edu.upeu.PharmaBackend.entity.Empleado;
import pe.edu.upeu.PharmaBackend.entity.Rol;
import pe.edu.upeu.PharmaBackend.entity.Usuario;
import pe.edu.upeu.PharmaBackend.exception.RecursoNoEncontradoException;
import pe.edu.upeu.PharmaBackend.exception.ReglaNegocioException;
import pe.edu.upeu.PharmaBackend.mapper.UsuarioMapper;
import pe.edu.upeu.PharmaBackend.repository.EmpleadoRepository;
import pe.edu.upeu.PharmaBackend.repository.RolRepository;
import pe.edu.upeu.PharmaBackend.repository.UsuarioRepository;
import pe.edu.upeu.PharmaBackend.service.service.UsuarioService;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UsuarioServiceImpl implements UsuarioService {

    private static final Logger log =
            LoggerFactory.getLogger(UsuarioServiceImpl.class);

    private final UsuarioRepository usuarioRepository;
    private final EmpleadoRepository empleadoRepository;
    private final RolRepository rolRepository;
    private final PasswordEncoder passwordEncoder;
    private final UsuarioMapper usuarioMapper;

    public UsuarioServiceImpl(
            UsuarioRepository usuarioRepository,
            EmpleadoRepository empleadoRepository,
            RolRepository rolRepository,
            PasswordEncoder passwordEncoder,
            UsuarioMapper usuarioMapper) {

        this.usuarioRepository = usuarioRepository;
        this.empleadoRepository = empleadoRepository;
        this.rolRepository = rolRepository;
        this.passwordEncoder = passwordEncoder;
        this.usuarioMapper = usuarioMapper;
    }

    @Override
    @Transactional
    public UsuarioResponseDTO crear(
            UsuarioRequestDTO request) {

        String username =
                request.getUsername()
                        .trim()
                        .toLowerCase(Locale.ROOT);

        log.info(
                "Registrando usuario={} empleadoId={} rolIds={}",
                username,
                request.getEmpleadoId(),
                request.getRolIds()
        );

        Empleado empleado =
                empleadoRepository
                        .findById(request.getEmpleadoId())
                        .orElseThrow(() ->
                                new RecursoNoEncontradoException(
                                        "Empleado no encontrado con id: "
                                                + request.getEmpleadoId()
                                )
                        );

        if (!Boolean.TRUE.equals(
                empleado.getEstado())) {

            throw new ReglaNegocioException(
                    "El empleado "
                            + empleado.getNombreCompleto()
                            + " está inactivo"
            );
        }

        if (usuarioRepository
                .existsByEmpleadoId(
                        empleado.getId()
                )) {

            throw new ReglaNegocioException(
                    "El empleado "
                            + empleado.getNombreCompleto()
                            + " ya tiene un usuario"
            );
        }

        if (usuarioRepository
                .existsByUsernameIgnoreCase(
                        username
                )) {

            throw new ReglaNegocioException(
                    "Ya existe un usuario con el nombre: "
                            + username
            );
        }

        List<Rol> roles =
                buscarRoles(request.getRolIds());

        if (request
                .getPassword()
                .getBytes(StandardCharsets.UTF_8)
                .length > 72) {

            throw new ReglaNegocioException(
                    "La contraseña no puede superar 72 bytes"
            );
        }

        Usuario usuario =
                new Usuario();

        usuario.setUsername(username);

        usuario.setPassword(
                passwordEncoder.encode(
                        request.getPassword()
                )
        );

        usuario.setEstado(true);
        usuario.setEmpleado(empleado);
        usuario.setRoles(
                new HashSet<>(roles)
        );

        Usuario guardado =
                usuarioRepository.save(usuario);

        log.info(
                "Usuario registrado id={} usuario={} roles={}",
                guardado.getId(),
                username,
                usuarioMapper.nombresRoles(roles)
        );

        return usuarioMapper
                .toUsuarioResponse(guardado);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UsuarioResponseDTO> listar() {

        return usuarioRepository
                .findAll(
                        Sort.by("username")
                )
                .stream()
                .map(
                        usuarioMapper::toUsuarioResponse
                )
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmpleadoResponseDTO> listarEmpleados() {

        Set<Long> conUsuario =
                usuarioRepository
                        .findEmpleadoIdsConUsuario();

        return empleadoRepository
                .findAll(
                        Sort.by(
                                "apellidos",
                                "nombres"
                        )
                )
                .stream()
                .map(e ->
                        usuarioMapper
                                .toEmpleadoResponse(
                                        e,
                                        conUsuario.contains(
                                                e.getId()
                                        )
                                )
                )
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RolResponseDTO> listarRoles() {

        return rolRepository
                .findAll(
                        Sort.by("nombre")
                )
                .stream()
                .map(
                        usuarioMapper::toRolResponse
                )
                .toList();
    }

    @Override
    @Transactional
    public UsuarioResponseDTO asignarRoles(
            Long usuarioId,
            String usernameActual,
            AsignarRolesRequestDTO request) {

        Usuario usuario =
                usuarioRepository
                        .findById(usuarioId)
                        .orElseThrow(() ->
                                new RecursoNoEncontradoException(
                                        "Usuario no encontrado con id: "
                                                + usuarioId
                                )
                        );

        List<Rol> roles =
                buscarRoles(request.getRolIds());

        boolean esMismoUsuario =
                usuario.getUsername()
                        .equalsIgnoreCase(
                                usernameActual
                        );

        boolean mantieneRolAdmin =
                roles.stream()
                        .anyMatch(rol ->
                                "ADMIN".equalsIgnoreCase(
                                        rol.getNombre()
                                )
                        );

        if (esMismoUsuario
                && !mantieneRolAdmin) {

            throw new ReglaNegocioException(
                    "Un ADMIN no puede quitarse a sí mismo el rol ADMIN"
            );
        }

        usuario.setRoles(
                new HashSet<>(roles)
        );

        Usuario guardado =
                usuarioRepository.save(usuario);

        log.info(
                "Roles actualizados: usuario={} roles={}",
                guardado.getUsername(),
                usuarioMapper.nombresRoles(
                        guardado.getRoles()
                )
        );

        return usuarioMapper
                .toUsuarioResponse(
                        guardado
                );
    }

    @Override
    @Transactional
    public UsuarioResponseDTO cambiarEstado(
            Long usuarioId,
            String usernameActual,
            CambiarEstadoUsuarioRequestDTO request) {

        Usuario usuario =
                usuarioRepository
                        .findById(usuarioId)
                        .orElseThrow(() ->
                                new RecursoNoEncontradoException(
                                        "Usuario no encontrado con id: "
                                                + usuarioId
                                )
                        );

        boolean esMismoUsuario =
                usuario.getUsername()
                        .equalsIgnoreCase(
                                usernameActual
                        );

        if (esMismoUsuario
                && Boolean.FALSE.equals(
                request.getEstado()
        )) {

            throw new ReglaNegocioException(
                    "Un ADMIN no puede desactivarse a sí mismo"
            );
        }

        usuario.setEstado(
                request.getEstado()
        );

        Usuario guardado =
                usuarioRepository.save(usuario);

        log.info(
                "Estado actualizado: usuario={} estado={}",
                guardado.getUsername(),
                guardado.getEstado()
        );

        return usuarioMapper
                .toUsuarioResponse(
                        guardado
                );
    }

    private List<Rol> buscarRoles(
            Set<Long> rolIds) {

        List<Rol> roles =
                rolRepository
                        .findAllById(rolIds);

        if (roles.size()
                != rolIds.size()) {

            Set<Long> encontrados =
                    roles.stream()
                            .map(Rol::getId)
                            .collect(
                                    Collectors.toSet()
                            );

            Set<Long> faltantes =
                    new HashSet<>(rolIds);

            faltantes.removeAll(
                    encontrados
            );

            throw new RecursoNoEncontradoException(
                    "Roles no encontrados: "
                            + faltantes
            );
        }

        return roles;
    }
}