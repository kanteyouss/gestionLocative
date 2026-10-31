package com.gestionLocative.gestionLocative.service;

import com.gestionLocative.gestionLocative.dto.*;
import com.gestionLocative.gestionLocative.entity.Immeuble;
import com.gestionLocative.gestionLocative.entity.Proprietaire;
import com.gestionLocative.gestionLocative.exception.NotFoundException;
import com.gestionLocative.gestionLocative.exception.RessourceUtiliseeException;
import com.gestionLocative.gestionLocative.repository.AppartementRepository;
import com.gestionLocative.gestionLocative.repository.ImmeubleRepository;
import com.gestionLocative.gestionLocative.repository.ProprietaireRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ImmeubleService extends AbstractCrudService {

    private final ImmeubleRepository immeubleRepository;
    private final ProprietaireRepository proprietaireRepository;
    private final AppartementRepository appartementRepository;
    private final AppartementService appartementService;

    public ImmeubleService(ImmeubleRepository immeubleRepository,
                           ProprietaireRepository proprietaireRepository,
                           AppartementRepository appartementRepository,
                           AppartementService appartementService) {
        super(immeubleRepository);
        this.immeubleRepository = immeubleRepository;
        this.proprietaireRepository = proprietaireRepository;
        this.appartementRepository = appartementRepository;
        this.appartementService = appartementService;
    }

    public Immeuble toEntity(ImmeubleRequestDto dto) {
        return Immeuble.builder()
                .nom(dto.getNom())
                .adresse(dto.getAdresse())
                .nbEtages(dto.getNbEtages())
                .proprietaireId(dto.getProprietaireId())
                .build();
    }

    @Override
    public ResponseDto toDto(Object entity) {
        Immeuble immeuble = (Immeuble) entity;
        Proprietaire proprietaire = null;
        if (immeuble.getProprietaireId() != null) {
            proprietaire = proprietaireRepository.findById(immeuble.getProprietaireId()).orElse(null);
        }
        ProprietaireResponseDto proprietaireDto = null;
        if (proprietaire != null) {
            proprietaireDto = ProprietaireResponseDto.builder()
                    .id(proprietaire.getId())
                    .nom(proprietaire.getNom())
                    .prenom(proprietaire.getPrenom())
                    .numero(proprietaire.getNumero())
                    .email(proprietaire.getEmail())
                    .adresse(proprietaire.getAdresse())
                    .build();
        }
        long nbAppartements = appartementRepository.countByImmeubleId(immeuble.getId());
        return ImmeubleResponseDto.builder()
                .id(immeuble.getId())
                .nom(immeuble.getNom())
                .adresse(immeuble.getAdresse())
                .nbEtages(immeuble.getNbEtages())
                .proprietaireId(immeuble.getProprietaireId())
                .proprietaire(proprietaireDto)
                .nbAppartements(nbAppartements)
                .build();
    }

    public Page<ResponseDto> getAll(int page, int taille) {
        return getAll(page, taille, Sort.by("adresse"));
    }

    @Override
    public ResponseDto create(RequestDto request) {
        ImmeubleRequestDto dto = (ImmeubleRequestDto) request;
        if (!proprietaireRepository.existsById(dto.getProprietaireId())) {
            throw new NotFoundException("Propriétaire non trouvé");
        }
        return toDto(immeubleRepository.save(toEntity(dto)));
    }

    @Override
    public ResponseDto update(Long id, RequestDto request) {
        ImmeubleRequestDto dto = (ImmeubleRequestDto) request;
        Immeuble immeuble = immeubleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Immeuble non trouvé"));
        if (!proprietaireRepository.existsById(dto.getProprietaireId())) {
            throw new NotFoundException("Propriétaire non trouvé");
        }
        int etageMax = appartementRepository.findByImmeubleId(id).stream()
                .mapToInt(a -> a.getEtage())
                .max()
                .orElse(0);
        if (dto.getNbEtages() < etageMax) {
            throw new IllegalArgumentException("Le nombre d'étages (" + dto.getNbEtages()
                    + ") ne peut pas être inférieur à l'étage le plus élevé des appartements (" + etageMax + ").");
        }
        immeuble.setNom(dto.getNom());
        immeuble.setAdresse(dto.getAdresse());
        immeuble.setNbEtages(dto.getNbEtages());
        immeuble.setProprietaireId(dto.getProprietaireId());
        return toDto(immeubleRepository.save(immeuble));
    }

    public ResponseDto delete(Long id) {
        Immeuble immeuble = immeubleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
        if (appartementRepository.existsByImmeubleId(id)) {
            throw new RessourceUtiliseeException("Immeuble", id, "Appartement");
        }
        immeubleRepository.deleteById(id);
        return toDto(immeuble);
    }

    public List<ResponseDto> getAppartementsByImmeuble(Long immeubleId) {
        return appartementService.getAppartementsByImmeuble(immeubleId);
    }
}